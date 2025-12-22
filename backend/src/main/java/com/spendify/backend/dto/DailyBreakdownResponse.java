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
public class DailyBreakdownResponse {
    private List<DailySummary> summaries;

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class DailySummary {
        private LocalDate date;
        private BigDecimal total;
    }
}
