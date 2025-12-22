package com.spendify.backend.repository;

import com.spendify.backend.entity.Category;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CategoryRepository extends JpaRepository<Category, Long> {
    List<Category> findByUserIdOrIsSystem(Long userId, boolean isSystem);
    Optional<Category> findByNameAndUserId(String name, Long userId);
}
