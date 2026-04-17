package com.spendify.backend.service;

import com.spendify.backend.dto.BudgetResponse;
import com.spendify.backend.dto.CreateBudgetRequest;
import com.spendify.backend.dto.UpdateBudgetRequest;
import com.spendify.backend.entity.Budget;
import com.spendify.backend.entity.User;
import com.spendify.backend.exception.ResourceNotFoundException;
import com.spendify.backend.repository.BudgetRepository;
import com.spendify.backend.repository.TransactionRepository;
import com.spendify.backend.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BudgetServiceTest {

    @Mock
    private BudgetRepository budgetRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private TransactionRepository transactionRepository;
    @Mock
    private SecurityContext securityContext;
    @Mock
    private Authentication authentication;

    @InjectMocks
    private BudgetService budgetService;

    private User testUser;

    @BeforeEach
    void setUp() {
        when(securityContext.getAuthentication()).thenReturn(authentication);
        SecurityContextHolder.setContext(securityContext);

        testUser = new User();
        testUser.setId(1L);
        testUser.setEmail("test@example.com");
    }

    @Test
    void createBudget_whenDetailsAreValid_shouldCreateAndReturnBudget() {
        // Given
        CreateBudgetRequest request = new CreateBudgetRequest(new BigDecimal("5000.00"), "2025-12");
        Budget savedBudget = Budget.builder()
                .id(1L)
                .user(testUser)
                .yearMonth(request.getYearMonth())
                .limit(request.getLimit())
                .build();

        when(authentication.getName()).thenReturn(testUser.getEmail());
        when(userRepository.findByEmail(testUser.getEmail())).thenReturn(Optional.of(testUser));
        when(budgetRepository.findByUserIdAndYearMonth(testUser.getId(), request.getYearMonth()))
                .thenReturn(Optional.empty());
        when(budgetRepository.save(any(Budget.class))).thenReturn(savedBudget);
        when(transactionRepository.sumAmountByUserIdAndTransactionDateBetween(any(), any(), any()))
                .thenReturn(BigDecimal.ZERO); // No spending yet

        // When
        BudgetResponse response = budgetService.createBudget(request);

        // Then
        assertThat(response).isNotNull();
        assertThat(response.getLimit()).isEqualTo(request.getLimit());
        assertThat(response.getYearMonth()).isEqualTo(request.getYearMonth());
        assertThat(response.getTotalSpent()).isEqualByComparingTo(BigDecimal.ZERO);

        verify(budgetRepository).save(argThat(budget ->
                budget.getUser().equals(testUser) &&
                budget.getYearMonth().equals("2025-12")
        ));
    }

    @Test
    void createBudget_whenBudgetForMonthExists_shouldThrowException() {
        // Given
        CreateBudgetRequest request = new CreateBudgetRequest(new BigDecimal("5000.00"), "2025-12");
        Budget existingBudget = new Budget();

        when(authentication.getName()).thenReturn(testUser.getEmail());
        when(userRepository.findByEmail(testUser.getEmail())).thenReturn(Optional.of(testUser));
        when(budgetRepository.findByUserIdAndYearMonth(testUser.getId(), request.getYearMonth()))
                .thenReturn(Optional.of(existingBudget));

        // When & Then
        assertThatThrownBy(() -> budgetService.createBudget(request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Budget for this month already exists.");

        verify(budgetRepository, never()).save(any(Budget.class));
    }

    @Test
    void getCurrentMonthBudget_whenBudgetExists_shouldReturnBudgetResponse() {
        // Given
        String currentYearMonth = java.time.YearMonth.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM"));
        Budget existingBudget = Budget.builder()
                .id(1L)
                .user(testUser)
                .yearMonth(currentYearMonth)
                .limit(new BigDecimal("1000.00"))
                .build();

        when(authentication.getName()).thenReturn(testUser.getEmail());
        when(userRepository.findByEmail(testUser.getEmail())).thenReturn(Optional.of(testUser));
        when(budgetRepository.findByUserIdAndYearMonth(testUser.getId(), currentYearMonth)).thenReturn(Optional.of(existingBudget));
        when(transactionRepository.sumAmountByUserIdAndTransactionDateBetween(any(), any(), any()))
                .thenReturn(new BigDecimal("250.00")); // Assume some spending

        // When
        BudgetResponse response = budgetService.getCurrentMonthBudget();

        // Then
        assertThat(response).isNotNull();
        assertThat(response.getId()).isEqualTo(existingBudget.getId());
        assertThat(response.getTotalSpent()).isEqualByComparingTo("250.00");
    }

    @Test
    void getCurrentMonthBudget_whenNoBudgetExists_shouldThrowResourceNotFoundException() {
        // Given
        String currentYearMonth = java.time.YearMonth.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM"));
        when(authentication.getName()).thenReturn(testUser.getEmail());
        when(userRepository.findByEmail(testUser.getEmail())).thenReturn(Optional.of(testUser));
        when(budgetRepository.findByUserIdAndYearMonth(testUser.getId(), currentYearMonth)).thenReturn(Optional.empty());

        // When & Then
        assertThatThrownBy(() -> budgetService.getCurrentMonthBudget())
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Budget for current month not found.");
    }

    @Test
    void getBudgetHistory_shouldReturnPageOfBudgetResponses() {
        // Given
        PageRequest pageable = PageRequest.of(0, 10);
        Budget budget1 = Budget.builder().id(1L).user(testUser).yearMonth("2025-11").limit(new BigDecimal("100")).build();
        Budget budget2 = Budget.builder().id(2L).user(testUser).yearMonth("2025-10").limit(new BigDecimal("200")).build();
        PageImpl<Budget> budgetPage = new PageImpl<>(List.of(budget1, budget2), pageable, 2);

        when(authentication.getName()).thenReturn(testUser.getEmail());
        when(userRepository.findByEmail(testUser.getEmail())).thenReturn(Optional.of(testUser));
        when(budgetRepository.findAllByUserId(testUser.getId(), pageable)).thenReturn(budgetPage);
        when(transactionRepository.sumAmountByUserIdAndTransactionDateBetween(any(), any(), any()))
                .thenReturn(BigDecimal.TEN); // Mock spending for mapping

        // When
        org.springframework.data.domain.Page<BudgetResponse> responsePage = budgetService.getBudgetHistory(pageable);

        // Then
        assertThat(responsePage).isNotNull();
        assertThat(responsePage.getTotalElements()).isEqualTo(2);
        assertThat(responsePage.getContent().get(0).getId()).isEqualTo(budget1.getId());
        assertThat(responsePage.getContent().get(0).getTotalSpent()).isEqualByComparingTo(BigDecimal.TEN);
    }

    @Test
    void updateBudget_whenValid_shouldUpdateAndReturnBudget() {
        // Given
        Long budgetId = 1L;
        Budget existingBudget = Budget.builder()
                .id(budgetId)
                .user(testUser)
                .yearMonth("2025-12")
                .limit(new BigDecimal("1000.00"))
                .build();
        
        UpdateBudgetRequest request = new UpdateBudgetRequest(new BigDecimal("1200.00"));
        
        when(authentication.getName()).thenReturn(testUser.getEmail());
        when(userRepository.findByEmail(testUser.getEmail())).thenReturn(Optional.of(testUser));
        when(budgetRepository.findById(budgetId)).thenReturn(Optional.of(existingBudget));
        when(budgetRepository.save(any(Budget.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(transactionRepository.sumAmountByUserIdAndTransactionDateBetween(any(), any(), any())).thenReturn(BigDecimal.ZERO);

        // When
        BudgetResponse response = budgetService.updateBudget(budgetId, request);

        // Then
        assertThat(response).isNotNull();
        assertThat(response.getLimit()).isEqualByComparingTo("1200.00");
        verify(budgetRepository).save(argThat(budget -> budget.getLimit().compareTo(new BigDecimal("1200.00")) == 0));
    }

    @Test
    void updateBudget_whenBudgetNotFound_shouldThrowException() {
        // Given
        Long budgetId = 999L;
        when(authentication.getName()).thenReturn(testUser.getEmail());
        when(userRepository.findByEmail(testUser.getEmail())).thenReturn(Optional.of(testUser));
        when(budgetRepository.findById(budgetId)).thenReturn(Optional.empty());

        // When & Then
        assertThatThrownBy(() -> budgetService.updateBudget(budgetId, new UpdateBudgetRequest()))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Budget not found");
    }

    @Test
    void updateBudget_whenUserNotAuthorized_shouldThrowException() {
        // Given
        User otherUser = new User();
        otherUser.setId(99L);
        
        Long budgetId = 1L;
        Budget existingBudget = Budget.builder()
                .id(budgetId)
                .user(otherUser) // Budget belongs to another user
                .yearMonth("2025-12")
                .limit(new BigDecimal("1000.00"))
                .build();
        
        when(authentication.getName()).thenReturn(testUser.getEmail());
        when(userRepository.findByEmail(testUser.getEmail())).thenReturn(Optional.of(testUser));
        when(budgetRepository.findById(budgetId)).thenReturn(Optional.of(existingBudget));

        // When & Then
        assertThatThrownBy(() -> budgetService.updateBudget(budgetId, new UpdateBudgetRequest()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("User not authorized to update this budget");
    }
}

