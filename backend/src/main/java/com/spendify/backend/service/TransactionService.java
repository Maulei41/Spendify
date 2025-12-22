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
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class TransactionService {

    private final TransactionRepository transactionRepository;
    private final CategoryRepository categoryRepository;
    private final UserRepository userRepository;

    @Transactional
    public TransactionResponse createTransaction(TransactionCreateRequest request) {
        User user = getCurrentUser();
        Category category = categoryRepository.findById(request.getCategoryId())
                .orElseThrow(() -> new ResourceNotFoundException("Category not found with id: " + request.getCategoryId()));

        if (category.getUser() != null && !category.getUser().getId().equals(user.getId())) {
            throw new UnauthorizedException("User not authorized for this category");
        }

        Transaction transaction = Transaction.builder()
                .amount(request.getAmount())
                .transactionDate(request.getTransactionDate())
                .description(request.getDescription())
                .category(category)
                .merchant(request.getMerchant())
                .user(user)
                .build();

        transaction = transactionRepository.save(transaction);
        return mapToTransactionResponse(transaction);
    }

    public Page<TransactionResponse> getTransactions(LocalDate startDate, LocalDate endDate, Long categoryId, String merchant, Pageable pageable) {
        User user = getCurrentUser();
        return transactionRepository.findByUserIdAndFilters(user.getId(), startDate, endDate, categoryId, merchant, pageable)
                .map(this::mapToTransactionResponse);
    }

    public TransactionResponse getTransactionById(Long id) {
        User user = getCurrentUser();
        Transaction transaction = transactionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Transaction not found with id: " + id));
        if (!transaction.getUser().getId().equals(user.getId())) {
            throw new UnauthorizedException("User not authorized for this transaction");
        }
        return mapToTransactionResponse(transaction);
    }

    @Transactional
    public TransactionResponse updateTransaction(Long id, TransactionUpdateRequest request) {
        User user = getCurrentUser();
        Transaction transaction = transactionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Transaction not found with id: " + id));

        if (!transaction.getUser().getId().equals(user.getId())) {
            throw new UnauthorizedException("User not authorized for this transaction");
        }

        if (request.getAmount() != null) {
            transaction.setAmount(request.getAmount());
        }
        if (request.getTransactionDate() != null) {
            transaction.setTransactionDate(request.getTransactionDate());
        }
        if (request.getDescription() != null) {
            transaction.setDescription(request.getDescription());
        }
        if (request.getCategoryId() != null) {
            Category category = categoryRepository.findById(request.getCategoryId())
                    .orElseThrow(() -> new ResourceNotFoundException("Category not found with id: " + request.getCategoryId()));
            if (category.getUser() != null && !category.getUser().getId().equals(user.getId())) {
                throw new UnauthorizedException("User not authorized for this category");
            }
            transaction.setCategory(category);
        }
        if (request.getMerchant() != null) {
            transaction.setMerchant(request.getMerchant());
        }

        transaction = transactionRepository.save(transaction);
        return mapToTransactionResponse(transaction);
    }

    @Transactional
    public void deleteTransaction(Long id) {
        User user = getCurrentUser();
        Transaction transaction = transactionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Transaction not found with id: " + id));

        if (!transaction.getUser().getId().equals(user.getId())) {
            throw new UnauthorizedException("User not authorized for this transaction");
        }
        transaction.setDeleted(true);
        transaction.setDeletedDate(LocalDateTime.now());
        transactionRepository.save(transaction);
    }

    @Transactional
    public TransactionResponse undoDeleteTransaction(Long id) {
        User user = getCurrentUser();
        // We need to fetch even soft-deleted ones here
        Transaction transaction = transactionRepository.findByIdIncludingDeleted(id)
                .orElseThrow(() -> new ResourceNotFoundException("Transaction not found with id: " + id));

        if (!transaction.getUser().getId().equals(user.getId())) {
            throw new UnauthorizedException("User not authorized for this transaction");
        }

        if (transaction.isDeleted() && (transaction.getDeletedDate() != null &&
                Duration.between(transaction.getDeletedDate(), LocalDateTime.now()).getSeconds() <= 10)) {
            transaction.setDeleted(false);
            transaction.setDeletedDate(null);
            transaction = transactionRepository.save(transaction);
        } else {
            throw new UnsupportedOperationException("Undo delete is not available for this transaction.");
        }
        return mapToTransactionResponse(transaction);
    }

    private User getCurrentUser() {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }

    private TransactionResponse mapToTransactionResponse(Transaction transaction) {
        return TransactionResponse.builder()
                .id(transaction.getId())
                .amount(transaction.getAmount())
                .transactionDate(transaction.getTransactionDate())
                .description(transaction.getDescription())
                .categoryId(transaction.getCategory().getId())
                .categoryName(transaction.getCategory().getName())
                .merchant(transaction.getMerchant())
                .build();
    }
}
