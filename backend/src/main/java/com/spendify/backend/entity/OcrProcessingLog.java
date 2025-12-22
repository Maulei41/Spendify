package com.spendify.backend.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "ocr_processing_logs")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OcrProcessingLog {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long id;

    private String inputImageName;

    private long processingTimeMs;

    private String tesseractVersion;

    @Lob
    private String detectedText;

    private double confidence;

    private String errorMessage;

    private boolean isSuccessful;

}
