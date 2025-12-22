package com.spendify.backend.dto;

import java.math.BigDecimal;

public interface CategorySpendingSummary {
    Long getCategoryId();
    String getCategoryName();
    BigDecimal getTotal();
}
