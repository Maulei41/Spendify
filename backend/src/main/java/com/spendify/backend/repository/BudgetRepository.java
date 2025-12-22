package com.spendify.backend.repository;

import com.spendify.backend.entity.Budget;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface BudgetRepository extends JpaRepository<Budget, Long> {
    Optional<Budget> findByUserIdAndYearMonth(Long userId, String yearMonth);
    Optional<Budget> findByUserIdAndYearMonthAndCategoryId(Long userId, String yearMonth, Long categoryId);
    Page<Budget> findAllByUserId(Long userId, Pageable pageable);
}
