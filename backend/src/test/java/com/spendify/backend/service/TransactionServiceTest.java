package com.spendify.backend.service;

import com.spendify.backend.dto.TransactionCreateRequest;
import com.spendify.backend.dto.TransactionResponse;
import com.spendify.backend.entity.Category;
import com.spendify.backend.entity.Transaction;
import com.spendify.backend.entity.User;
import com.spendify.backend.exception.ResourceNotFoundException;
import com.spendify.backend.exception.UnauthorizedException;
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
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TransactionServiceTest {

    @Mock
    private TransactionRepository transactionRepository;
    @Mock
    private CategoryRepository categoryRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private SecurityContext securityContext;
    @Mock
    private Authentication authentication;

    @InjectMocks
    private TransactionService transactionService;

    private User currentUser;
    private User otherUser;
    private Category userOwnedCategory;

    @BeforeEach
    void setUp() {
        when(securityContext.getAuthentication()).thenReturn(authentication);
        SecurityContextHolder.setContext(securityContext);

        currentUser = new User();
        currentUser.setId(1L);
        currentUser.setEmail("current@user.com");
        
        otherUser = new User();
        otherUser.setId(2L);
        otherUser.setEmail("other@user.com");

        userOwnedCategory = new Category();
        userOwnedCategory.setId(10L);
        userOwnedCategory.setName("Groceries");
        userOwnedCategory.setUser(currentUser); // Owned by the current user
        userOwnedCategory.setSystem(false);
    }

    private void mockCurrentUser() {
        when(authentication.getName()).thenReturn(currentUser.getEmail());
        when(userRepository.findByEmail(currentUser.getEmail())).thenReturn(Optional.of(currentUser));
    }

    @Test
    void createTransaction_whenDetailsAreValid_shouldCreateAndReturnTransaction() {
        // Given
        mockCurrentUser();
        TransactionCreateRequest request = TransactionCreateRequest.builder()
                .amount(new BigDecimal("99.99"))
                .transactionDate(LocalDate.now())
                .categoryId(userOwnedCategory.getId())
                .merchant("SuperMart")
                .build();
        
        Transaction savedTransaction = Transaction.builder()
                .id(1L)
                .amount(request.getAmount())
                .transactionDate(request.getTransactionDate())
                .category(userOwnedCategory)
                .user(currentUser)
                .merchant(request.getMerchant())
                .build();

        when(categoryRepository.findById(request.getCategoryId())).thenReturn(Optional.of(userOwnedCategory));
        when(transactionRepository.save(any(Transaction.class))).thenReturn(savedTransaction);

        // When
        TransactionResponse response = transactionService.createTransaction(request);

        // Then
        assertThat(response).isNotNull();
        assertThat(response.getAmount()).isEqualByComparingTo("99.99");
        assertThat(response.getMerchant()).isEqualTo("SuperMart");
        assertThat(response.getCategoryId()).isEqualTo(userOwnedCategory.getId());

        verify(transactionRepository).save(argThat(transaction ->
                transaction.getUser().equals(currentUser) &&
                transaction.getCategory().equals(userOwnedCategory)
        ));
    }

    @Test
    void createTransaction_whenCategoryNotFound_shouldThrowResourceNotFoundException() {
        // Given
        mockCurrentUser();
        TransactionCreateRequest request = new TransactionCreateRequest();
        request.setCategoryId(999L); // Non-existent category

        when(categoryRepository.findById(999L)).thenReturn(Optional.empty());

        // When & Then
        assertThatThrownBy(() -> transactionService.createTransaction(request))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Category not found with id: 999");
        
        verify(transactionRepository, never()).save(any());
    }

    @Test
    void createTransaction_whenCategoryNotAuthorized_shouldThrowUnauthorizedException() {
        // Given
        mockCurrentUser();
        Category otherUserCategory = new Category();
        otherUserCategory.setId(20L);
        otherUserCategory.setUser(otherUser); // Belongs to another user
        otherUserCategory.setSystem(false);

        TransactionCreateRequest request = new TransactionCreateRequest();
        request.setCategoryId(otherUserCategory.getId());

        when(categoryRepository.findById(otherUserCategory.getId())).thenReturn(Optional.of(otherUserCategory));

        // When & Then
        assertThatThrownBy(() -> transactionService.createTransaction(request))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("User not authorized for this category");
        
        verify(transactionRepository, never()).save(any());
    }
}
