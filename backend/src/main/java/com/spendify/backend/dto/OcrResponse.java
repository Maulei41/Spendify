package com.spendify.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class OcrResponse {
    private String merchant;
    private LocalDate date;
    private BigDecimal amount;
    private List<String> items;
    private double confidence;
    private List<String> warnings;
    private boolean requiresManualReview;
}
