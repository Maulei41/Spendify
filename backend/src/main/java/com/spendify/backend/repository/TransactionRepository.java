package com.spendify.backend.repository;

import com.spendify.backend.entity.Transaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction, Long> {

    @Query("SELECT t FROM Transaction t WHERE t.user.id = :userId " +
            "AND (:startDate IS NULL OR t.transactionDate >= :startDate) " +
            "AND (:endDate IS NULL OR t.transactionDate <= :endDate) " +
            "AND (:categoryId IS NULL OR t.category.id = :categoryId) " +
            "AND (:merchant IS NULL OR t.merchant LIKE %:merchant%)")
    Page<Transaction> findByUserIdAndFilters(
            @Param("userId") Long userId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate,
            @Param("categoryId") Long categoryId,
            @Param("merchant") String merchant,
            Pageable pageable
    );

    @Query("SELECT t FROM Transaction t WHERE t.id = :id")
    Optional<Transaction> findByIdIncludingDeleted(@Param("id") Long id);

    @Query("SELECT SUM(t.amount) FROM Transaction t WHERE t.user.id = :userId AND t.category.id = :categoryId AND t.transactionDate BETWEEN :startDate AND :endDate")
    BigDecimal sumAmountByUserIdAndCategoryIdAndTransactionDateBetween(
            @Param("userId") Long userId,
            @Param("categoryId") Long categoryId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate
    );

    @Modifying
    @Query("UPDATE Transaction t SET t.category.id = :newCategoryId WHERE t.user.id = :userId AND t.category.id = :oldCategoryId")
    void reassignCategory(
            @Param("userId") Long userId,
            @Param("oldCategoryId") Long oldCategoryId,
            @Param("newCategoryId") Long newCategoryId
    );

    long countByCategoryId(Long categoryId);

    @Query("SELECT c.id as categoryId, c.name as categoryName, SUM(t.amount) as total " +
           "FROM Transaction t JOIN t.category c " +
           "WHERE t.user.id = :userId AND t.transactionDate BETWEEN :startDate AND :endDate " +
           "GROUP BY c.id, c.name ORDER BY total DESC")
    List<com.spendify.backend.dto.CategorySpendingSummary> findSpendingByCategory(
            @Param("userId") Long userId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate
    );

    @Query("SELECT t.transactionDate as date, SUM(t.amount) as total " +
           "FROM Transaction t " +
           "WHERE t.user.id = :userId AND t.transactionDate BETWEEN :startDate AND :endDate " +
           "GROUP BY t.transactionDate ORDER BY t.transactionDate")
    List<com.spendify.backend.dto.DailySpending> findSpendingByDay(
            @Param("userId") Long userId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate
    );
}
