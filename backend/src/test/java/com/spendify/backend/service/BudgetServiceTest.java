package com.spendify.backend.service;

import com.spendify.backend.dto.BudgetResponse;
import com.spendify.backend.dto.CreateBudgetRequest;
import com.spendify.backend.entity.Budget;
import com.spendify.backend.entity.Category;
import com.spendify.backend.entity.User;
import com.spendify.backend.exception.ResourceNotFoundException;
import com.spendify.backend.repository.BudgetRepository;
import com.spendify.backend.repository.CategoryRepository;
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
    private CategoryRepository categoryRepository;
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
    private Category testCategory;

    @BeforeEach
    void setUp() {
        when(securityContext.getAuthentication()).thenReturn(authentication);
        SecurityContextHolder.setContext(securityContext);

        testUser = new User();
        testUser.setId(1L);
        testUser.setEmail("test@example.com");

        testCategory = new Category();
        testCategory.setId(10L);
        testCategory.setName("Food");
    }

    @Test
    void createBudget_whenDetailsAreValid_shouldCreateAndReturnBudget() {
        // Given
        CreateBudgetRequest request = new CreateBudgetRequest(new BigDecimal("5000.00"), "2025-12", testCategory.getId());
        Budget savedBudget = Budget.builder()
                .id(1L)
                .user(testUser)
                .category(testCategory)
                .yearMonth(request.getYearMonth())
                .limit(request.getLimit())
                .build();

        when(authentication.getName()).thenReturn(testUser.getEmail());
        when(userRepository.findByEmail(testUser.getEmail())).thenReturn(Optional.of(testUser));
        when(categoryRepository.findById(request.getCategoryId())).thenReturn(Optional.of(testCategory));
        when(budgetRepository.findByUserIdAndYearMonthAndCategoryId(testUser.getId(), request.getYearMonth(), request.getCategoryId()))
                .thenReturn(Optional.empty());
        when(budgetRepository.save(any(Budget.class))).thenReturn(savedBudget);
        when(transactionRepository.sumAmountByUserIdAndCategoryIdAndTransactionDateBetween(any(), any(), any(), any()))
                .thenReturn(BigDecimal.ZERO); // No spending yet

        // When
        BudgetResponse response = budgetService.createBudget(request);

        // Then
        assertThat(response).isNotNull();
        assertThat(response.getLimit()).isEqualTo(request.getLimit());
        assertThat(response.getYearMonth()).isEqualTo(request.getYearMonth());
        assertThat(response.getCategoryId()).isEqualTo(request.getCategoryId());
        assertThat(response.getTotalSpent()).isEqualByComparingTo(BigDecimal.ZERO);

        verify(budgetRepository).save(argThat(budget ->
                budget.getUser().equals(testUser) &&
                budget.getCategory().equals(testCategory) &&
                budget.getYearMonth().equals("2025-12")
        ));
    }

    @Test
    void createBudget_whenBudgetForMonthAndCategoryExists_shouldThrowException() {
        // Given
        CreateBudgetRequest request = new CreateBudgetRequest(new BigDecimal("5000.00"), "2025-12", testCategory.getId());
        Budget existingBudget = new Budget();

        when(authentication.getName()).thenReturn(testUser.getEmail());
        when(userRepository.findByEmail(testUser.getEmail())).thenReturn(Optional.of(testUser));
        when(categoryRepository.findById(request.getCategoryId())).thenReturn(Optional.of(testCategory));
        when(budgetRepository.findByUserIdAndYearMonthAndCategoryId(testUser.getId(), request.getYearMonth(), request.getCategoryId()))
                .thenReturn(Optional.of(existingBudget));

        // When & Then
        assertThatThrownBy(() -> budgetService.createBudget(request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Budget for this category and month already exists.");

        verify(budgetRepository, never()).save(any(Budget.class));
    }

    @Test
    void createBudget_whenCategoryNotFound_shouldThrowException() {
        // Given
        CreateBudgetRequest request = new CreateBudgetRequest(new BigDecimal("5000.00"), "2025-12", 999L); // Non-existent category

        when(authentication.getName()).thenReturn(testUser.getEmail());
        when(userRepository.findByEmail(testUser.getEmail())).thenReturn(Optional.of(testUser));
        when(categoryRepository.findById(request.getCategoryId())).thenReturn(Optional.empty());

        // When & Then
        assertThatThrownBy(() -> budgetService.createBudget(request))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Category not found");

        verify(budgetRepository, never()).save(any(Budget.class));
    }
}
