package com.spendify.backend.service;

import com.spendify.backend.dto.CategorySpendingSummary;
import com.spendify.backend.dto.CategorySummaryResponse;
import com.spendify.backend.dto.DailyBreakdownResponse;
import com.spendify.backend.dto.DailySpending;
import com.spendify.backend.entity.User;
import com.spendify.backend.repository.TransactionRepository;
import com.spendify.backend.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnalyticsServiceTest {

    @Mock
    private TransactionRepository transactionRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private SecurityContext securityContext;
    @Mock
    private Authentication authentication;

    @InjectMocks
    private AnalyticsService analyticsService;

    private User currentUser;
    private LocalDate startDate;
    private LocalDate endDate;

    @BeforeEach
    void setUp() {
        when(securityContext.getAuthentication()).thenReturn(authentication);
        SecurityContextHolder.setContext(securityContext);

        currentUser = new User();
        currentUser.setId(1L);
        currentUser.setEmail("current@user.com");

        startDate = LocalDate.of(2025, 12, 1);
        endDate = LocalDate.of(2025, 12, 31);
        
        when(authentication.getName()).thenReturn(currentUser.getEmail());
        when(userRepository.findByEmail(currentUser.getEmail())).thenReturn(Optional.of(currentUser));
    }

    @Test
    void getCategorySpendingSummary_whenTransactionsExist_shouldReturnAggregatedSummary() {
        // Given
        CategorySpendingSummary foodSummary = createCategorySpendingSummary(1L, "Food", new BigDecimal("300.00"));
        CategorySpendingSummary transportSummary = createCategorySpendingSummary(2L, "Transport", new BigDecimal("100.00"));
        
        List<CategorySpendingSummary> spendingList = List.of(foodSummary, transportSummary);
        when(transactionRepository.findSpendingByCategory(currentUser.getId(), startDate, endDate)).thenReturn(spendingList);

        // When
        CategorySummaryResponse response = analyticsService.getCategorySpendingSummary(startDate, endDate);

        // Then
        assertThat(response).isNotNull();
        assertThat(response.getTotal()).isEqualByComparingTo("400.00");
        assertThat(response.getSummaries()).hasSize(2);
        
        // Check Food summary (300/400 = 75%)
        CategorySummaryResponse.CategorySummary foodResponse = response.getSummaries().stream()
            .filter(s -> s.getCategoryName().equals("Food")).findFirst().orElse(null);
        assertThat(foodResponse).isNotNull();
        assertThat(foodResponse.getTotal()).isEqualByComparingTo("300.00");
        assertThat(foodResponse.getPercentage()).isEqualTo(0.75);

        // Check Transport summary (100/400 = 25%)
        CategorySummaryResponse.CategorySummary transportResponse = response.getSummaries().stream()
            .filter(s -> s.getCategoryName().equals("Transport")).findFirst().orElse(null);
        assertThat(transportResponse).isNotNull();
        assertThat(transportResponse.getTotal()).isEqualByComparingTo("100.00");
        assertThat(transportResponse.getPercentage()).isEqualTo(0.25);
    }
    
    @Test
    void getCategorySpendingSummary_whenNoTransactionsExist_shouldReturnEmptySummary() {
        // Given
        when(transactionRepository.findSpendingByCategory(currentUser.getId(), startDate, endDate)).thenReturn(List.of());

        // When
        CategorySummaryResponse response = analyticsService.getCategorySpendingSummary(startDate, endDate);

        // Then
        assertThat(response).isNotNull();
        assertThat(response.getTotal()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(response.getSummaries()).isEmpty();
    }
    
    @Test
    void getDailySpendingBreakdown_whenTransactionsExist_shouldReturnDailySummaries() {
        // Given
        DailySpending day1 = createDailySpending(LocalDate.of(2025, 12, 1), new BigDecimal("50.00"));
        DailySpending day2 = createDailySpending(LocalDate.of(2025, 12, 3), new BigDecimal("75.50"));
        
        List<DailySpending> dailyList = List.of(day1, day2);
        when(transactionRepository.findSpendingByDay(currentUser.getId(), startDate, endDate)).thenReturn(dailyList);

        // When
        DailyBreakdownResponse response = analyticsService.getDailySpendingBreakdown(startDate, endDate);

        // Then
        assertThat(response).isNotNull();
        assertThat(response.getSummaries()).hasSize(2);
        assertThat(response.getSummaries().get(0).getDate()).isEqualTo(day1.getDate());
        assertThat(response.getSummaries().get(0).getTotal()).isEqualByComparingTo("50.00");
        assertThat(response.getSummaries().get(1).getDate()).isEqualTo(day2.getDate());
        assertThat(response.getSummaries().get(1).getTotal()).isEqualByComparingTo("75.50");
    }

    // Helper method to create mock CategorySpendingSummary
    private CategorySpendingSummary createCategorySpendingSummary(Long id, String name, BigDecimal total) {
        return new CategorySpendingSummary() {
            @Override public Long getCategoryId() { return id; }
            @Override public String getCategoryName() { return name; }
            @Override public BigDecimal getTotal() { return total; }
        };
    }
    
    // Helper method to create mock DailySpending
    private DailySpending createDailySpending(LocalDate date, BigDecimal total) {
        return new DailySpending() {
            @Override public LocalDate getDate() { return date; }
            @Override public BigDecimal getTotal() { return total; }
        };
    }
}
