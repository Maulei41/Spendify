package com.spendify.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import jakarta.validation.constraints.DecimalMin;
import java.math.BigDecimal;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class UpdateBudgetRequest {

    @DecimalMin(value = "5000", message = "Limit must be at least 5,000")
    private BigDecimal limit;
}
