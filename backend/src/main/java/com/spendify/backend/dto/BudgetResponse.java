package com.spendify.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class BudgetResponse {
    private Long id;
    private BigDecimal limit;
    private String yearMonth;
    private Long categoryId;
    private String categoryName;
    private BigDecimal totalSpent;
    private double percentageSpent;
    private BigDecimal projectedTotal;
    private BigDecimal overspendAmount;
}
