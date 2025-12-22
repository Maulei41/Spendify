package com.spendify.backend.service;

import com.spendify.backend.dto.CategorySpendingSummary;
import com.spendify.backend.dto.CategorySummaryResponse;
import com.spendify.backend.dto.DailySpending;
import com.spendify.backend.dto.DailyBreakdownResponse;
import com.spendify.backend.entity.User;
import com.spendify.backend.exception.ResourceNotFoundException;
import com.spendify.backend.repository.TransactionRepository;
import com.spendify.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AnalyticsService {

    private final TransactionRepository transactionRepository;
    private final UserRepository userRepository;

    public CategorySummaryResponse getCategorySpendingSummary(LocalDate startDate, LocalDate endDate) {
        User user = getCurrentUser();
        List<CategorySpendingSummary> spendingByCategory = transactionRepository.findSpendingByCategory(user.getId(), startDate, endDate);

        BigDecimal total = spendingByCategory.stream()
                .map(CategorySpendingSummary::getTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<CategorySummaryResponse.CategorySummary> summaries = spendingByCategory.stream()
                .map(summary -> new CategorySummaryResponse.CategorySummary(
                        summary.getCategoryId(),
                        summary.getCategoryName(),
                        summary.getTotal(),
                        total.compareTo(BigDecimal.ZERO) > 0 ?
                                summary.getTotal().divide(total, 4, RoundingMode.HALF_UP).doubleValue() : 0
                ))
                .collect(Collectors.toList());

        return new CategorySummaryResponse(summaries, total);
    }

    public DailyBreakdownResponse getDailySpendingBreakdown(LocalDate startDate, LocalDate endDate) {
        User user = getCurrentUser();
        List<DailySpending> spendingByDay = transactionRepository.findSpendingByDay(user.getId(), startDate, endDate);
        
        List<DailyBreakdownResponse.DailySummary> summaries = spendingByDay.stream()
                .map(summary -> new DailyBreakdownResponse.DailySummary(
                        summary.getDate(),
                        summary.getTotal()
                ))
                .collect(Collectors.toList());

        return new DailyBreakdownResponse(summaries);
    }

    private User getCurrentUser() {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }
}
