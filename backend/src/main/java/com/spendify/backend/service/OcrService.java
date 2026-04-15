package com.spendify.backend.service;

import com.spendify.backend.dto.OcrResponse;
import com.spendify.backend.entity.OcrProcessingLog;
import com.spendify.backend.repository.OcrProcessingLogRepository;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * OCR service that uses Tesseract for receipt text extraction
 * and applies rule-based post-processing to extract entities.
 * 
 * Note: A new Tesseract instance is created per request because
 * Tesseract's native code is not thread-safe.
 */
@Service
public class OcrService {

    private static final Logger log = LoggerFactory.getLogger(OcrService.class);
    private static final int MAX_IMAGE_DIMENSION = 8000;

    // Regex patterns from Python code
    private static final Pattern DATE_PATTERN_1 = Pattern.compile("\\d{4}[-/]\\d{2}[-/]\\d{2}");
    private static final Pattern DATE_PATTERN_2 = Pattern.compile("\\d{2}[-/]\\d{2}[-/]\\d{2,4}");
    private static final Pattern TOTAL_PATTERN = Pattern.compile("[\\$€£¥]?\\s*\\d{1,3}(,\\d{3})*(\\.\\d{2})");
    private static final Pattern UNWANTED_COMPANY_PATTERN = Pattern.compile("^(?!.*(RECEIPT|INVOICE|TAX|SUBTOTAL)).*$");

    private final OcrProcessingLogRepository ocrProcessingLogRepository;

    @Autowired
    public OcrService(OcrProcessingLogRepository ocrProcessingLogRepository) {
        this.ocrProcessingLogRepository = ocrProcessingLogRepository;
    }

    @Value("${tesseract.data.path:tessdata}")
    private String tesseractDataPath;

    @Value("${tesseract.language:eng+chi_sim}")
    private String tesseractLanguage;


    /**
     * Process a receipt image and extract structured data using Tesseract OCR.
     */
    public OcrResponse processReceipt(MultipartFile file) {
        long startTime = System.currentTimeMillis();
        OcrProcessingLog orclog = new OcrProcessingLog();
        orclog.setInputImageName(file.getOriginalFilename());

        try {
            validateImage(file);

            // Read and validate the image
            BufferedImage image = ImageIO.read(file.getInputStream());
            if (image == null) {
                throw new IllegalArgumentException("Failed to read image file: unsupported or corrupted format");
            }
            if (image.getWidth() == 0 || image.getHeight() == 0) {
                throw new IllegalArgumentException("Invalid image dimensions: 0x0");
            }

            // Reject excessively large images to prevent native memory issues
            if (image.getWidth() > MAX_IMAGE_DIMENSION || image.getHeight() > MAX_IMAGE_DIMENSION) {
                throw new IllegalArgumentException(
                        String.format("Image dimensions (%dx%d) exceed the maximum allowed limit of %dx%d.",
                                image.getWidth(), image.getHeight(), MAX_IMAGE_DIMENSION, MAX_IMAGE_DIMENSION)
                );
            }

            log.info("Processing image: {}x{} pixels, type: {}", image.getWidth(), image.getHeight(), image.getType());


            // Convert to TYPE_BYTE_GRAY to avoid native Tesseract crashes
            // Tesseract is known to crash with RGBA, custom color models, etc.
            BufferedImage grayImage = convertToGrayscale(image);
            log.info("Converted image to grayscale: {}x{} pixels, type: {}", grayImage.getWidth(), grayImage.getHeight(), grayImage.getType());


            // Create a new Tesseract instance per request (native code is not thread-safe)
            Tesseract tesseract = new Tesseract();

            // Configure Tesseract
            tesseract.setDatapath(tesseractDataPath);
            tesseract.setLanguage(tesseractLanguage);
            tesseract.setPageSegMode(3); // Fully automatic page segmentation with OSD (more robust)

            // Validate that required traineddata files exist before calling doOCR()
            // Missing traineddata causes SIGSEGV in native Tesseract code
            validateTrainedDataFiles(tesseractDataPath, tesseractLanguage);

            // Perform OCR on the normalized grayscale image
            String extractedText = tesseract.doOCR(grayImage);
            
            log.info("Text extracted by Tesseract:\n{}", extractedText);
            orclog.setDetectedText(extractedText);

            // Split into lines for post-processing
            List<String> textLines = Arrays.asList(extractedText.split("\n"));

            // Extract entities using rule-based post-processing (ported from Python)
            String company = extractCompany(textLines);
            String dateStr = extractDate(textLines);
            String totalStr = extractTotal(textLines);

            // Parse date string to LocalDate
            LocalDate date = parseDate(dateStr);

            // Parse total string to BigDecimal
            BigDecimal amount = parseAmount(totalStr);

            log.info("Extracted entities: Company={}, Date={}, Amount={}", company, date, amount);

            long endTime = System.currentTimeMillis();
            orclog.setProcessingTimeMs(endTime - startTime);
            orclog.setSuccessful(true);
            ocrProcessingLogRepository.save(orclog);

            // Build response
            List<String> warnings = new ArrayList<>();
            if (company.isEmpty() || company.equals("Unknown")) {
                warnings.add("Merchant name not detected");
            }
            if (date == null) {
                warnings.add("Date not detected");
            }
            if (amount == null) {
                warnings.add("Total amount not detected");
            }

            double confidence = calculateConfidence(extractedText);

            return OcrResponse.builder()
                    .merchant(company.isEmpty() ? "Unknown Merchant" : company)
                    .date(date)
                    .amount(amount)
                    .confidence(confidence)
                    .warnings(warnings)
                    .requiresManualReview(warnings.size() > 1)
                    .build();

        } catch (TesseractException e) {
            long endTime = System.currentTimeMillis();
            orclog.setProcessingTimeMs(endTime - startTime);
            orclog.setSuccessful(false);
            orclog.setErrorMessage("Tesseract OCR processing failed: " + e.getMessage());
            ocrProcessingLogRepository.save(orclog);
            throw new RuntimeException("OCR processing failed: " + e.getMessage(), e);
        } catch (UnsatisfiedLinkError | ExceptionInInitializerError e) {
            long endTime = System.currentTimeMillis();
            orclog.setProcessingTimeMs(endTime - startTime);
            orclog.setSuccessful(false);
            orclog.setErrorMessage("Tesseract native library error: " + e.getMessage());
            ocrProcessingLogRepository.save(orclog);
            log.error("Tesseract native crash detected", e);
            throw new RuntimeException("OCR service unavailable: native library error", e);
        } catch (Error e) {
            long endTime = System.currentTimeMillis();
            orclog.setProcessingTimeMs(endTime - startTime);
            orclog.setSuccessful(false);
            orclog.setErrorMessage("Fatal error during OCR: " + e.getMessage());
            ocrProcessingLogRepository.save(orclog);
            log.error("Fatal error in OCR processing", e);
            throw new RuntimeException("OCR service error: " + e.getMessage(), e);
        } catch (IOException e) {
            long endTime = System.currentTimeMillis();
            orclog.setProcessingTimeMs(endTime - startTime);
            orclog.setSuccessful(false);
            orclog.setErrorMessage(e.getMessage());
            ocrProcessingLogRepository.save(orclog);
            throw new RuntimeException("OCR processing failed: " + e.getMessage(), e);
        } catch (Exception e) {
            long endTime = System.currentTimeMillis();
            orclog.setProcessingTimeMs(endTime - startTime);
            orclog.setSuccessful(false);
            orclog.setErrorMessage(e.getMessage());
            ocrProcessingLogRepository.save(orclog);
            throw new RuntimeException("An unexpected error occurred during OCR processing: " + e.getMessage(), e);
        }
    }

    /**
     * Extract company/merchant name from receipt text lines.
     * Ported from Python clean_company and rule-based extraction.
     */
    private String extractCompany(List<String> textLines) {
        if (textLines.isEmpty()) {
            return "";
        }

        // Rule: Company is usually in the first two lines
        // Must not be a number and must not contain unwanted words
        for (int i = 0; i < Math.min(2, textLines.size()); i++) {
            String line = textLines.get(i).trim();
            
            // Check if line matches unwanted pattern
            Matcher unwantedMatcher = UNWANTED_COMPANY_PATTERN.matcher(line);
            if (!unwantedMatcher.matches()) {
                continue;
            }
            
            // Check if it's not just a number
            if (!isNumber(line)) {
                String cleaned = cleanCompany(line);
                if (!cleaned.isEmpty()) {
                    return cleaned;
                }
            }
        }

        return "";
    }

    /**
     * Clean company name by removing unwanted suffixes.
     */
    private String cleanCompany(String text) {
        // Remove trailing patterns like "(ABC123XYZ)" or "(ABC)"
        Pattern[] patterns = {
            Pattern.compile("(\\d+[^0-9]*[A-Z]+)$"),
            Pattern.compile("\\([A-Z]*\\d+[^0-9]*[A-Z]+\\).*$"),
            Pattern.compile("\\([A-Z\\s]+[\\)]*$")
        };

        for (Pattern pattern : patterns) {
            Matcher matcher = pattern.matcher(text.trim());
            if (matcher.find()) {
                int idx = text.indexOf(matcher.group().trim());
                text = text.substring(0, idx).trim();
                break;
            }
        }

        return text.trim();
    }

    /**
     * Extract date from receipt text lines.
     * Ported from Python extract_date with fallback rules.
     */
    private String extractDate(List<String> textLines) {
        for (String line : textLines) {
            String date = extractDateFromLine(line.trim());
            if (!date.isEmpty()) {
                return date;
            }
        }
        return "";
    }

    /**
     * Extract date from a single line using regex patterns.
     */
    private String extractDateFromLine(String text) {
        Matcher matcher1 = DATE_PATTERN_1.matcher(text);
        if (matcher1.find()) {
            return matcher1.group().trim();
        }

        Matcher matcher2 = DATE_PATTERN_2.matcher(text);
        if (matcher2.find()) {
            return matcher2.group().trim();
        }

        return "";
    }

    /**
     * Extract total amount from receipt text lines.
     * Ported from Python extract_total with fallback rules.
     */
    private String extractTotal(List<String> textLines) {
        String found = "";
        
        // Look for total/amount keywords followed by a number
        for (int i = 0; i < textLines.size(); i++) {
            String line = textLines.get(i).trim();
            
            // Check if line contains total/amount keywords
            boolean hasTotalKeyword = line.toUpperCase().matches(".*\\b(TOTAL|AMOUNT|AMT|DUE)\\b.*");
            boolean hasExcludedKeyword = line.toUpperCase().matches(".*\\b(EX|SUB|CASH|QTY|TAX|INVOICE)\\b.*");
            
            if (hasTotalKeyword && !hasExcludedKeyword) {
                String total = extractTotalFromLine(line);
                if (!total.isEmpty()) {
                    found = total;
                    break;
                }
            }
        }

        // If not found with keywords, search for any valid total pattern
        if (found.isEmpty()) {
            for (String line : textLines) {
                String total = extractTotalFromLine(line.trim());
                if (!total.isEmpty()) {
                    found = total;
                    break;
                }
            }
        }

        return found;
    }

    /**
     * Extract total amount from a single line using regex.
     */
    private String extractTotalFromLine(String text) {
        Matcher matcher = TOTAL_PATTERN.matcher(text);
        if (matcher.find()) {
            return matcher.group().trim();
        }
        return "";
    }

    /**
     * Parse date string to LocalDate object.
     */
    private LocalDate parseDate(String dateStr) {
        if (dateStr == null || dateStr.isEmpty()) {
            return null;
        }

        DateTimeFormatter[] formatters = {
            DateTimeFormatter.ofPattern("yyyy-MM-dd"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd"),
            DateTimeFormatter.ofPattern("MM-dd-yyyy"),
            DateTimeFormatter.ofPattern("MM/dd/yyyy"),
            DateTimeFormatter.ofPattern("yy-MM-dd"),
            DateTimeFormatter.ofPattern("yy/MM/dd"),
            DateTimeFormatter.ofPattern("MM-dd-yy"),
            DateTimeFormatter.ofPattern("MM/dd/yy")
        };

        for (DateTimeFormatter formatter : formatters) {
            try {
                return LocalDate.parse(dateStr, formatter);
            } catch (DateTimeParseException e) {
                // Try next formatter
            }
        }

        log.warn("Could not parse date: {}", dateStr);
        return null;
    }

    /**
     * Parse amount string to BigDecimal object.
     */
    private BigDecimal parseAmount(String amountStr) {
        if (amountStr == null || amountStr.isEmpty()) {
            return null;
        }

        try {
            // Remove currency symbols and commas
            String cleaned = amountStr.replaceAll("[^\\d.]", "");
            return new BigDecimal(cleaned);
        } catch (NumberFormatException e) {
            log.warn("Could not parse amount: {}", amountStr);
            return null;
        }
    }

    /**
     * Check if a string is a number.
     */
    private boolean isNumber(String text) {
        try {
            Double.parseDouble(text.trim());
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * Calculate confidence score based on extracted text quality.
     */
    private double calculateConfidence(String text) {
        if (text == null || text.trim().isEmpty()) {
            return 0.0;
        }

        // Simple heuristic: longer text with more lines = better confidence
        int lineCount = text.split("\n").length;
        int charCount = text.length();

        // Base confidence calculation
        double confidence = Math.min(1.0, (lineCount * 0.1 + charCount * 0.001));

        // Reduce confidence if text is very short
        if (charCount < 50) {
            confidence *= 0.5;
        }

        return Math.round(confidence * 100.0) / 100.0;
    }

    /**
     * Validate the uploaded image file.
     */
    private void validateImage(MultipartFile file) throws IOException {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("File is empty");
        }

        String contentType = file.getContentType();
        if (!"image/jpeg".equals(contentType) &&
                !"image/png".equals(contentType) &&
                !"image/webp".equals(contentType) &&
                !"image/bmp".equals(contentType) &&
                !"image/gif".equals(contentType)) {
            throw new IllegalArgumentException("Unsupported image format: " + contentType);
        }

        if (file.getSize() > 10 * 1024 * 1024) { // 10MB
            throw new IllegalArgumentException("File too large (max 10MB)");
        }
    }

    /**
     * Validate that required Tesseract trained data files exist.
     * Missing traineddata causes SIGSEGV in native Tesseract code.
     */
    private void validateTrainedDataFiles(String datapath, String languages) {
        String[] langs = languages.split("\\+");
        for (String lang : langs) {
            String langTrimmed = lang.trim();
            java.io.File trainedDataFile = new java.io.File(datapath, langTrimmed + ".traineddata");
            if (!trainedDataFile.exists()) {
                throw new IllegalStateException(
                    "Missing Tesseract traineddata file: " + trainedDataFile.getAbsolutePath() +
                    ". OCR requires language training data files. Ensure '" + langTrimmed + ".traineddata' " +
                    "exists in the configured datapath: '" + datapath + "'.");
            }
        }
        log.info("Validated trained data files exist for languages: {}", languages);
    }

    private BufferedImage convertToGrayscale(BufferedImage originalImage) {
        if (originalImage.getType() == BufferedImage.TYPE_BYTE_GRAY) {
            return originalImage; // Already grayscale
        }

        // Create a new grayscale image
        BufferedImage grayImage = new BufferedImage(
                originalImage.getWidth(),
                originalImage.getHeight(),
                BufferedImage.TYPE_BYTE_GRAY);

        // Draw the original image onto the grayscale image, which performs the conversion
        grayImage.getGraphics().drawImage(originalImage, 0, 0, null);
        grayImage.getGraphics().dispose();

        return grayImage;
    }
}
