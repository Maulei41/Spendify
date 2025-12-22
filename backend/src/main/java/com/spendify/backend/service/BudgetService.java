package com.spendify.backend.service;

import com.spendify.backend.dto.BudgetResponse;
import com.spendify.backend.dto.CreateBudgetRequest;
import com.spendify.backend.dto.UpdateBudgetRequest;
import com.spendify.backend.entity.Budget;
import com.spendify.backend.entity.Category;
import com.spendify.backend.entity.User;
import com.spendify.backend.exception.ResourceNotFoundException;
import com.spendify.backend.repository.BudgetRepository;
import com.spendify.backend.repository.CategoryRepository;
import com.spendify.backend.repository.TransactionRepository;
import com.spendify.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

@Service
@RequiredArgsConstructor
public class BudgetService {

    private final BudgetRepository budgetRepository;
    private final CategoryRepository categoryRepository;
    private final UserRepository userRepository;
    private final TransactionRepository transactionRepository;

    @Transactional
    public BudgetResponse createBudget(CreateBudgetRequest request) {
        User user = getCurrentUser();
        Category category = categoryRepository.findById(request.getCategoryId())
                .orElseThrow(() -> new ResourceNotFoundException("Category not found"));
        
        budgetRepository.findByUserIdAndYearMonthAndCategoryId(user.getId(), request.getYearMonth(), request.getCategoryId())
            .ifPresent(b -> {
                throw new IllegalStateException("Budget for this category and month already exists.");
            });

        Budget budget = Budget.builder()
                .user(user)
                .category(category)
                .yearMonth(request.getYearMonth())
                .limit(request.getLimit())
                .build();
        
        budget = budgetRepository.save(budget);
        return mapToBudgetResponse(budget);
    }

    public BudgetResponse getCurrentMonthBudget() {
        User user = getCurrentUser();
        String currentYearMonth = YearMonth.now().format(DateTimeFormatter.ofPattern("yyyy-MM"));
        
        // This is a simplified logic. In a real scenario, you'd likely want to return
        // a list of budgets if there can be multiple per month (e.g., per category).
        Budget budget = budgetRepository.findByUserIdAndYearMonth(user.getId(), currentYearMonth)
                .orElseThrow(() -> new ResourceNotFoundException("Budget for current month not found."));

        return mapToBudgetResponse(budget);
    }

    public Page<BudgetResponse> getBudgetHistory(Pageable pageable) {
        User user = getCurrentUser();
        return budgetRepository.findAllByUserId(user.getId(), pageable)
                .map(this::mapToBudgetResponse);
    }

    @Transactional
    public BudgetResponse updateBudget(Long id, UpdateBudgetRequest request) {
        User user = getCurrentUser();
        Budget budget = budgetRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Budget not found"));

        if (!budget.getUser().getId().equals(user.getId())) {
            throw new IllegalStateException("User not authorized to update this budget");
        }

        budget.setLimit(request.getLimit());
        budget = budgetRepository.save(budget);
        return mapToBudgetResponse(budget);
    }

    private BudgetResponse mapToBudgetResponse(Budget budget) {
        YearMonth yearMonth = YearMonth.parse(budget.getYearMonth());
        LocalDate startDate = yearMonth.atDay(1);
        LocalDate endDate = yearMonth.atEndOfMonth();

        BigDecimal totalSpent = transactionRepository.sumAmountByUserIdAndCategoryIdAndTransactionDateBetween(
                budget.getUser().getId(),
                budget.getCategory().getId(),
                startDate,
                endDate
        );
        if (totalSpent == null) {
            totalSpent = BigDecimal.ZERO;
        }

        double percentageSpent = 0;
        if (budget.getLimit().compareTo(BigDecimal.ZERO) > 0) {
            percentageSpent = totalSpent.divide(budget.getLimit(), 4, RoundingMode.HALF_UP).doubleValue();
        }
        
        long daysInMonth = yearMonth.lengthOfMonth();
        long daysElapsed = ChronoUnit.DAYS.between(startDate, LocalDate.now().plusDays(1));
        if (daysElapsed <= 0) daysElapsed = 1;
        if (daysElapsed > daysInMonth) daysElapsed = daysInMonth;

        BigDecimal dailyAverage = totalSpent.divide(BigDecimal.valueOf(daysElapsed), 2, RoundingMode.HALF_UP);
        BigDecimal projectedTotal = dailyAverage.multiply(BigDecimal.valueOf(daysInMonth));
        BigDecimal overspendAmount = BigDecimal.ZERO;
        if (projectedTotal.compareTo(budget.getLimit()) > 0) {
            overspendAmount = projectedTotal.subtract(budget.getLimit());
        }


        return BudgetResponse.builder()
                .id(budget.getId())
                .limit(budget.getLimit())
                .yearMonth(budget.getYearMonth())
                .categoryId(budget.getCategory().getId())
                .categoryName(budget.getCategory().getName())
                .totalSpent(totalSpent)
                .percentageSpent(percentageSpent)
                .projectedTotal(projectedTotal)
                .overspendAmount(overspendAmount)
                .build();
    }

    private User getCurrentUser() {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }
}
