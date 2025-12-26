package com.spendify.backend.service;

import com.spendify.backend.dto.OcrResponse;
import com.spendify.backend.entity.OcrProcessingLog;
import com.spendify.backend.repository.OcrProcessingLogRepository;
import ij.IJ;
import ij.ImagePlus;
// import ij.plugin.ImageConverter; // Removed
// import ij.plugin.filter.AutoLocalThreshold; // Removed
import ij.plugin.filter.GaussianBlur;
import ij.process.ColorProcessor; // Added import
import ij.process.ImageProcessor;
import lombok.RequiredArgsConstructor;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.File; // Added import
import java.nio.file.Files; // Added import
import java.nio.file.Paths; // Added import
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
@RequiredArgsConstructor
public class OcrService {

    private static final Logger log = LoggerFactory.getLogger(OcrService.class);

    private final OcrProcessingLogRepository ocrProcessingLogRepository;

    @Value("${tesseract.data-path}")
    private String tesseractDataPath;

    public OcrResponse processReceipt(MultipartFile file) {
        long startTime = System.currentTimeMillis();
        OcrProcessingLog orclog = new OcrProcessingLog();
        orclog.setInputImageName(file.getOriginalFilename());

        try {
            // Image validation placeholder
            validateImage(file);

            BufferedImage image = ImageIO.read(file.getInputStream());
            if (image == null) {
                throw new IOException("Could not read image file.");
            }

            // Preprocess the image for better OCR accuracy
            BufferedImage processedImage = preprocessImage(image);


            Tesseract tesseract = new Tesseract();
            tesseract.setDatapath(tesseractDataPath);
            tesseract.setLanguage("eng+chi_tra");
            tesseract.setOcrEngineMode(1); // LSTM mode
            tesseract.setPageSegMode(6);

            String text = tesseract.doOCR(processedImage);
            log.info("Extracted text from OCR: \n{}", text);
            orclog.setDetectedText(text);

            // Basic parsing logic (placeholders)
            String merchant = parseMerchant(text);
            BigDecimal amount = parseAmount(text);

            long endTime = System.currentTimeMillis();
            orclog.setProcessingTimeMs(endTime - startTime);
            orclog.setSuccessful(true);
            ocrProcessingLogRepository.save(orclog);

            return OcrResponse.builder()
                    .merchant(merchant)
                    .amount(amount)
                    .confidence(0.8) // Placeholder
                    .warnings(new ArrayList<>())
                    .build();

        } catch (IOException | TesseractException e) {
            long endTime = System.currentTimeMillis();
            orclog.setProcessingTimeMs(endTime - startTime);
            orclog.setSuccessful(false);
            orclog.setErrorMessage(e.getMessage());
            ocrProcessingLogRepository.save(orclog);
            // Consider a more specific exception
            throw new RuntimeException("OCR processing failed: " + e.getMessage(), e);
        }
    }

    private void validateImage(MultipartFile file) throws IOException {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("File is empty");
        }

        String contentType = file.getContentType();
        if (!"image/jpeg".equals(contentType) && !"image/png".equals(contentType) && !"image/webp".equals(contentType)) {
            throw new IllegalArgumentException("Unsupported image format: " + contentType);
        }

        if (file.getSize() > 10 * 1024 * 1024) { // 10MB
            throw new IllegalArgumentException("File too large (max 10MB)");
        }

        BufferedImage img = ImageIO.read(file.getInputStream());
        if (img == null) {
            throw new IOException("Invalid or corrupted image");
        }
        if (img.getWidth() * img.getHeight() < 500 * 500) {
            log.warn("Low resolution image: {}x{}", img.getWidth(), img.getHeight());
            // You could reject or just warn
        }
    }

    private String parseMerchant(String text) {
        String[] lines = text.split("\n");
        for (String line : lines) {
            line = line.trim();
            if (line.length() > 3 && line.length() < 50 &&
                !line.matches(".*\\d{2,}.*") &&  // Avoid lines with many numbers (dates, items)
                Character.isUpperCase(line.charAt(0))) {
                return line;
            }
        }
        return lines.length > 0 ? lines[0].trim() : "Unknown Merchant";
    }

    private BigDecimal parseAmount(String text) {
        // Common total keywords
        String[] totalKeywords = {"total", "amount", "balance", "due", "paid", "subtotal", "grand total"};
        
        String[] lines = text.toLowerCase().split("\n");
        BigDecimal bestAmount = null;
        double highestValue = 0;

        Pattern amountPattern = Pattern.compile("\\$?\\s*[0-9]{1,3}(?:,?[0-9]{3})*\\.[0-9]{2}");

        for (String line : lines) {
            boolean hasTotalKeyword = false;
            for (String keyword : totalKeywords) {
                if (line.contains(keyword)) {
                    hasTotalKeyword = true;
                    break;
                }
            }

            Matcher matcher = amountPattern.matcher(line);
            while (matcher.find()) {
                String match = matcher.group().replaceAll("[^0-9.]", "");
                try {
                    BigDecimal value = new BigDecimal(match);
                    // Prioritize lines with "total" keywords, otherwise take largest amount
                    if (hasTotalKeyword || value.doubleValue() > highestValue) {
                        bestAmount = value;
                        highestValue = value.doubleValue();
                    }
                } catch (NumberFormatException ignored) {}
            }
        }

        return bestAmount;
    }

    private BufferedImage preprocessImage(BufferedImage originalImage) {
        ImagePlus imp = new ImagePlus("receipt", originalImage);
        ImageProcessor ip = imp.getProcessor();

        // 1. Convert to grayscale
        if (ip instanceof ColorProcessor) {
            ip = ((ColorProcessor) ip).convertToByteProcessor();
            imp.setProcessor(ip);
        }

        // 2. Detect and invert if dark background (e.g., black screenshot with white text)
        if (isDarkBackground(ip)) {
            ip.invert();
            log.info("Dark background detected — image inverted");
        }

        // 3. Reduce noise with light Gaussian blur
        new GaussianBlur().blurGaussian(ip, 1.0, 1.0, 0.01);

        // 4. Aggressive contrast enhancement (better than binary mask)
        ip.multiply(1.5);  // Increase contrast
        ip.gamma(0.8);     // Optional: slight gamma correction

        // Optional: Sharpen slightly
        ip.sharpen();

        // 5. Resize/upscale for small fonts (very common in receipts)
        int newWidth = (int) (ip.getWidth() * 2.0);
        int newHeight = (int) (ip.getHeight() * 2.0);
        ip = ip.resize(newWidth, newHeight);
        imp.setProcessor(ip);

        BufferedImage result = imp.getBufferedImage();

        // Optional: Save for debugging
        saveDebugImage(result, "preprocessed");

        return result;
    }

    private boolean isDarkBackground(ImageProcessor ip) {
        // Sample corner pixels (usually background in receipts)
        int width = ip.getWidth();
        int height = ip.getHeight();
        int[] corners = {
            ip.getPixel(10, 10),
            ip.getPixel(width - 10, 10),
            ip.getPixel(10, height - 10),
            ip.getPixel(width - 10, height - 10)
        };

        int sum = 0;
        for (int pixel : corners) {
            sum += pixel;
        }
        int avg = sum / corners.length;

        // If average corner brightness < 128 → dark background
        return avg < 128;
    }

    private void saveDebugImage(BufferedImage image, String suffix) {
        try {
            String tempDir = "C:\\Users\\hoyin_av1y1a9\\Downloads";
            Files.createDirectories(Paths.get(tempDir));
            File outputfile = new File(tempDir, suffix + "_" + System.currentTimeMillis() + ".png");
            ImageIO.write(image, "png", outputfile);
            log.info("Saved debug image to: {}", outputfile.getAbsolutePath());
        } catch (IOException e) {
            log.error("Failed to save debug image '{}': {}", suffix, e.getMessage());
        }
    }
}
