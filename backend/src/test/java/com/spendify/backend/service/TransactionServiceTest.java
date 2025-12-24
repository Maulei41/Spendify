package com.spendify.backend.service;

import com.spendify.backend.dto.TransactionCreateRequest;
import com.spendify.backend.dto.TransactionResponse;
import com.spendify.backend.dto.TransactionUpdateRequest;
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

    @Test
    void getTransactionById_whenTransactionExistsAndBelongsToUser_shouldReturnTransactionResponse() {
        // Given
        mockCurrentUser();
        Long transactionId = 1L;
        Transaction existingTransaction = Transaction.builder()
                .id(transactionId)
                .amount(new BigDecimal("50.00"))
                .transactionDate(LocalDate.now())
                .category(userOwnedCategory)
                .user(currentUser)
                .merchant("Cafe")
                .build();
        when(transactionRepository.findById(transactionId)).thenReturn(Optional.of(existingTransaction));

        // When
        TransactionResponse response = transactionService.getTransactionById(transactionId);

        // Then
        assertThat(response).isNotNull();
        assertThat(response.getId()).isEqualTo(transactionId);
        assertThat(response.getAmount()).isEqualByComparingTo("50.00");
        assertThat(response.getCategoryName()).isEqualTo(userOwnedCategory.getName());
    }

    @Test
    void getTransactionById_whenTransactionNotFound_shouldThrowResourceNotFoundException() {
        // Given
        mockCurrentUser();
        Long nonExistentTransactionId = 999L;
        when(transactionRepository.findById(nonExistentTransactionId)).thenReturn(Optional.empty());

        // When & Then
        assertThatThrownBy(() -> transactionService.getTransactionById(nonExistentTransactionId))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Transaction not found with id: " + nonExistentTransactionId);
    }

    @Test
    void getTransactionById_whenTransactionBelongsToAnotherUser_shouldThrowUnauthorizedException() {
        // Given
        mockCurrentUser();
        Long transactionId = 1L;
        Transaction otherUserTransaction = Transaction.builder()
                .id(transactionId)
                .amount(new BigDecimal("25.00"))
                .transactionDate(LocalDate.now())
                .category(userOwnedCategory)
                .user(otherUser) // Belongs to other user
                .merchant("Cinema")
                .build();
        when(transactionRepository.findById(transactionId)).thenReturn(Optional.of(otherUserTransaction));

        // When & Then
        assertThatThrownBy(() -> transactionService.getTransactionById(transactionId))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("User not authorized for this transaction");
    }

    @Test
    void updateTransaction_whenDetailsAreValidAndTransactionBelongsToUser_shouldUpdateAndReturnTransaction() {
        // Given
        mockCurrentUser();
        Long transactionId = 1L;
        Transaction existingTransaction = Transaction.builder()
                .id(transactionId)
                .amount(new BigDecimal("50.00"))
                .transactionDate(LocalDate.now().minusDays(1))
                .category(userOwnedCategory)
                .user(currentUser)
                .merchant("Old Merchant")
                .build();

        Category newCategory = new Category();
        newCategory.setId(11L);
        newCategory.setName("Transport");
        newCategory.setUser(currentUser);
        newCategory.setSystem(false);

        TransactionUpdateRequest updateRequest = TransactionUpdateRequest.builder()
                .amount(new BigDecimal("75.00"))
                .transactionDate(LocalDate.now())
                .description("Updated desc")
                .categoryId(newCategory.getId())
                .merchant("New Merchant")
                .build();
        
        Transaction updatedTransaction = Transaction.builder() // The expected saved transaction
                .id(transactionId)
                .amount(new BigDecimal("75.00"))
                .transactionDate(LocalDate.now())
                .description("Updated desc")
                .category(newCategory)
                .user(currentUser)
                .merchant("New Merchant")
                .build();


        when(transactionRepository.findById(transactionId)).thenReturn(Optional.of(existingTransaction));
        when(categoryRepository.findById(newCategory.getId())).thenReturn(Optional.of(newCategory));
        when(transactionRepository.save(any(Transaction.class))).thenReturn(updatedTransaction); // Return the fully updated object

        // When
        TransactionResponse response = transactionService.updateTransaction(transactionId, updateRequest);

        // Then
        assertThat(response).isNotNull();
        assertThat(response.getId()).isEqualTo(transactionId);
        assertThat(response.getAmount()).isEqualByComparingTo("75.00");
        assertThat(response.getMerchant()).isEqualTo("New Merchant");
        assertThat(response.getCategoryName()).isEqualTo("Transport");
        assertThat(response.getDescription()).isEqualTo("Updated desc");

        verify(transactionRepository).save(argThat(tx -> 
            tx.getId().equals(transactionId) &&
            tx.getAmount().compareTo(new BigDecimal("75.00")) == 0 &&
            tx.getCategory().getId().equals(newCategory.getId()) &&
            tx.getDescription().equals("Updated desc") &&
            tx.getMerchant().equals("New Merchant")
        ));
    }

    @Test
    void updateTransaction_whenTransactionNotFound_shouldThrowResourceNotFoundException() {
        // Given
        mockCurrentUser();
        Long nonExistentTransactionId = 999L;
        TransactionUpdateRequest updateRequest = new TransactionUpdateRequest();
        when(transactionRepository.findById(nonExistentTransactionId)).thenReturn(Optional.empty());

        // When & Then
        assertThatThrownBy(() -> transactionService.updateTransaction(nonExistentTransactionId, updateRequest))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Transaction not found with id: " + nonExistentTransactionId);
    }

    @Test
    void updateTransaction_whenTransactionBelongsToAnotherUser_shouldThrowUnauthorizedException() {
        // Given
        mockCurrentUser();
        Long transactionId = 1L;
        Transaction otherUserTransaction = Transaction.builder()
                .id(transactionId)
                .amount(new BigDecimal("25.00"))
                .transactionDate(LocalDate.now())
                .category(userOwnedCategory)
                .user(otherUser) // Belongs to other user
                .merchant("Cinema")
                .build();
        TransactionUpdateRequest updateRequest = new TransactionUpdateRequest(); // Request doesn't matter for auth check

        when(transactionRepository.findById(transactionId)).thenReturn(Optional.of(otherUserTransaction));

        // When & Then
        assertThatThrownBy(() -> transactionService.updateTransaction(transactionId, updateRequest))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("User not authorized for this transaction");
    }

    @Test
    void updateTransaction_whenUpdatingWithUnauthorizedCategory_shouldThrowUnauthorizedException() {
        // Given
        mockCurrentUser();
        Long transactionId = 1L;
        Transaction existingTransaction = Transaction.builder()
                .id(transactionId)
                .amount(new BigDecimal("50.00"))
                .transactionDate(LocalDate.now())
                .category(userOwnedCategory)
                .user(currentUser)
                .merchant("Cafe")
                .build();

        Category unauthorizedCategory = new Category();
        unauthorizedCategory.setId(99L);
        unauthorizedCategory.setUser(otherUser); // Category belongs to another user
        unauthorizedCategory.setSystem(false);

        TransactionUpdateRequest updateRequest = TransactionUpdateRequest.builder()
                .categoryId(unauthorizedCategory.getId())
                .build();

        when(transactionRepository.findById(transactionId)).thenReturn(Optional.of(existingTransaction));
        when(categoryRepository.findById(unauthorizedCategory.getId())).thenReturn(Optional.of(unauthorizedCategory));

        // When & Then
        assertThatThrownBy(() -> transactionService.updateTransaction(transactionId, updateRequest))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("User not authorized for this category");
    }

    @Test
    void deleteTransaction_whenTransactionExistsAndBelongsToUser_shouldSoftDelete() {
        // Given
        mockCurrentUser();
        Long transactionId = 1L;
        Transaction existingTransaction = Transaction.builder()
                .id(transactionId)
                .user(currentUser)
                .isDeleted(false) // Initially not deleted
                .build();
        
        when(transactionRepository.findById(transactionId)).thenReturn(Optional.of(existingTransaction));

        // When
        transactionService.deleteTransaction(transactionId);

        // Then
        verify(transactionRepository).save(argThat(transaction ->
                transaction.getId().equals(transactionId) &&
                transaction.isDeleted() && // Should now be deleted
                transaction.getDeletedDate() != null
        ));
    }

    @Test
    void deleteTransaction_whenTransactionBelongsToAnotherUser_shouldThrowUnauthorizedException() {
        // Given
        mockCurrentUser();
        Long transactionId = 1L;
        Transaction otherUserTransaction = Transaction.builder().id(transactionId).user(otherUser).build();
        when(transactionRepository.findById(transactionId)).thenReturn(Optional.of(otherUserTransaction));

        // When & Then
        assertThatThrownBy(() -> transactionService.deleteTransaction(transactionId))
                .isInstanceOf(UnauthorizedException.class);
        
        verify(transactionRepository, never()).save(any());
    }

    @Test
    void undoDeleteTransaction_whenRecentlyDeleted_shouldRestoreTransaction() {
        // Given
        mockCurrentUser();
        Long transactionId = 1L;
        Transaction deletedTransaction = Transaction.builder()
                .id(transactionId)
                .user(currentUser)
                .isDeleted(true)
                .deletedDate(java.time.LocalDateTime.now().minusSeconds(5)) // Deleted 5 seconds ago
                .category(userOwnedCategory)
                .build();

        when(transactionRepository.findByIdIncludingDeleted(transactionId)).thenReturn(Optional.of(deletedTransaction));
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(invocation -> invocation.getArgument(0));


        // When
        TransactionResponse response = transactionService.undoDeleteTransaction(transactionId);

        // Then
        assertThat(response).isNotNull();
        verify(transactionRepository).save(argThat(transaction ->
                transaction.getId().equals(transactionId) &&
                !transaction.isDeleted() && // Should be restored
                transaction.getDeletedDate() == null
        ));
    }
    
    @Test
    void undoDeleteTransaction_whenNotRecentlyDeleted_shouldThrowException() {
        // Given
        mockCurrentUser();
        Long transactionId = 1L;
        Transaction deletedTransaction = Transaction.builder()
                .id(transactionId)
                .user(currentUser)
                .isDeleted(true)
                .deletedDate(java.time.LocalDateTime.now().minusSeconds(15)) // Deleted 15 seconds ago
                .build();
        
        when(transactionRepository.findByIdIncludingDeleted(transactionId)).thenReturn(Optional.of(deletedTransaction));

        // When & Then
        assertThatThrownBy(() -> transactionService.undoDeleteTransaction(transactionId))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage("Undo delete is not available for this transaction.");
    }
    
    @Test
    void undoDeleteTransaction_whenTransactionIsNotDeleted_shouldThrowException() {
        // Given
        mockCurrentUser();
        Long transactionId = 1L;
        Transaction activeTransaction = Transaction.builder()
                .id(transactionId)
                .user(currentUser)
                .isDeleted(false) // Not deleted
                .build();

        when(transactionRepository.findByIdIncludingDeleted(transactionId)).thenReturn(Optional.of(activeTransaction));
        
        // When & Then
        assertThatThrownBy(() -> transactionService.undoDeleteTransaction(transactionId))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage("Undo delete is not available for this transaction.");
    }
    
    @Test
    void undoDeleteTransaction_whenNotAuthorized_shouldThrowException() {
        // Given
        mockCurrentUser();
        Long transactionId = 1L;
        Transaction otherUserDeletedTransaction = Transaction.builder()
                .id(transactionId)
                .user(otherUser) // Belongs to other user
                .isDeleted(true)
                .deletedDate(java.time.LocalDateTime.now().minusSeconds(5))
                .build();
        
        when(transactionRepository.findByIdIncludingDeleted(transactionId)).thenReturn(Optional.of(otherUserDeletedTransaction));

        // When & Then
        assertThatThrownBy(() -> transactionService.undoDeleteTransaction(transactionId))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("User not authorized for this transaction");
    }
}


