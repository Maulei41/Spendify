package com.spendify.backend.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public interface DailySpending {
    LocalDate getDate();
    BigDecimal getTotal();
}
