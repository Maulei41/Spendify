package com.spendify.backend.service;

import com.spendify.backend.dto.OcrResponse;
import com.spendify.backend.entity.OcrProcessingLog;
import com.spendify.backend.repository.OcrProcessingLogRepository;
import lombok.RequiredArgsConstructor;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class OcrService {

    private final OcrProcessingLogRepository ocrProcessingLogRepository;
    private final Tesseract tesseract;

    public OcrResponse processReceipt(MultipartFile file) {
        long startTime = System.currentTimeMillis();
        OcrProcessingLog log = new OcrProcessingLog();
        log.setInputImageName(file.getOriginalFilename());
        //log.setTesseractVersion(tesseract.getVersion()); // Removed this line

        try {
            // Image validation placeholder
            validateImage(file);

            BufferedImage image = ImageIO.read(file.getInputStream());
            if (image == null) {
                throw new IOException("Could not read image file.");
            }

            String text = tesseract.doOCR(image);
            log.setDetectedText(text);

            // Basic parsing logic (placeholders)
            String merchant = parseMerchant(text);
            BigDecimal amount = parseAmount(text);

            long endTime = System.currentTimeMillis();
            log.setProcessingTimeMs(endTime - startTime);
            log.setSuccessful(true);
            ocrProcessingLogRepository.save(log);

            return OcrResponse.builder()
                    .merchant(merchant)
                    .amount(amount)
                    .confidence(0.8) // Placeholder
                    .warnings(new ArrayList<>())
                    .build();

        } catch (IOException | TesseractException e) {
            long endTime = System.currentTimeMillis();
            log.setProcessingTimeMs(endTime - startTime);
            log.setSuccessful(false);
            log.setErrorMessage(e.getMessage());
            ocrProcessingLogRepository.save(log);
            // Consider a more specific exception
            throw new RuntimeException("OCR processing failed: " + e.getMessage(), e);
        }
    }

    private void validateImage(MultipartFile file) {
        // Placeholder for image validation logic
        // - Format: JPEG, PNG, WEBP
        // - Size: < 10MB
        // - Resolution: >= 2MP
    }

    private String parseMerchant(String text) {
        // Placeholder for merchant parsing logic
        return "Extracted Merchant";
    }

    private BigDecimal parseAmount(String text) {
        // This is a very basic amount parser. A more robust solution is needed.
        Pattern pattern = Pattern.compile("Total[:\\s]+([0-9,]+\\.[0-9]{2})");
        Matcher matcher = pattern.matcher(text);
        if (matcher.find()) {
            try {
                return new BigDecimal(matcher.group(1).replace(",", ""));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
}
