package com.spendify.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class CreateBudgetRequest {

    @NotNull(message = "Limit is required")
    @DecimalMin(value = "5000", message = "Limit must be at least 5,000")
    private BigDecimal limit;

    @NotNull(message = "YearMonth is required")
    @Pattern(regexp = "^\\d{4}-\\d{2}$", message = "YearMonth must be in YYYY-MM format")
    private String yearMonth;

    @NotNull(message = "Category ID is required")
    private Long categoryId;
}
