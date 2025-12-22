package com.spendify.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class CategorySummaryResponse {
    private List<CategorySummary> summaries;
    private BigDecimal total;

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class CategorySummary {
        private Long categoryId;
        private String categoryName;
        private BigDecimal total;
        private double percentage;
    }
}
