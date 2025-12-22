package com.spendify.backend.service;

import com.spendify.backend.dto.CategoryResponse;
import com.spendify.backend.dto.CreateCategoryRequest;
import com.spendify.backend.dto.UpdateCategoryRequest;
import com.spendify.backend.entity.Category;
import com.spendify.backend.entity.User;
import com.spendify.backend.exception.ResourceNotFoundException;
import com.spendify.backend.repository.CategoryRepository;
import com.spendify.backend.repository.TransactionRepository;
import com.spendify.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CategoryService {

    private final CategoryRepository categoryRepository;
    private final UserRepository userRepository;
    private final TransactionRepository transactionRepository;

    public List<CategoryResponse> getAllCategories() {
        User user = getCurrentUser();
        return categoryRepository.findByUserIdOrIsSystem(user.getId(), true)
                .stream()
                .map(this::mapToCategoryResponse)
                .collect(Collectors.toList());
    }

    @Transactional
    public CategoryResponse createCategory(CreateCategoryRequest request) {
        User user = getCurrentUser();
        categoryRepository.findByNameAndUserId(request.getName(), user.getId())
                .ifPresent(c -> {
                    throw new IllegalStateException("Category with this name already exists.");
                });

        Category category = Category.builder()
                .name(request.getName())
                .color(request.getColor())
                .icon(request.getIcon())
                .user(user)
                .isSystem(false)
                .build();
        
        category = categoryRepository.save(category);
        return mapToCategoryResponse(category);
    }

    @Transactional
    public CategoryResponse updateCategory(Long id, UpdateCategoryRequest request) {
        User user = getCurrentUser();
        Category category = categoryRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Category not found"));

        if (category.isSystem() || !category.getUser().getId().equals(user.getId())) {
            throw new IllegalStateException("User not authorized to update this category.");
        }

        if (request.getName() != null) {
            category.setName(request.getName());
        }
        if (request.getColor() != null) {
            category.setColor(request.getColor());
        }
        if (request.getIcon() != null) {
            category.setIcon(request.getIcon());
        }
        
        category = categoryRepository.save(category);
        return mapToCategoryResponse(category);
    }

    @Transactional
    public void deleteCategory(Long id, Long reassignToId) {
        User user = getCurrentUser();
        Category category = categoryRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Category not found"));

        if (category.isSystem() || !category.getUser().getId().equals(user.getId())) {
            throw new IllegalStateException("User not authorized to delete this category.");
        }

        Category reassignCategory;
        if (reassignToId != null) {
            reassignCategory = categoryRepository.findById(reassignToId)
                    .orElseThrow(() -> new ResourceNotFoundException("Reassignment category not found."));
            if (!reassignCategory.getUser().getId().equals(user.getId()) && !reassignCategory.isSystem()) {
                throw new IllegalStateException("User not authorized to reassign to this category.");
            }
        } else {
            reassignCategory = categoryRepository.findByNameAndUserId("Other", null)
                .orElseThrow(() -> new IllegalStateException("Default 'Other' category not found."));
        }
        
        transactionRepository.reassignCategory(user.getId(), id, reassignCategory.getId());
        
        categoryRepository.delete(category);
    }

    private CategoryResponse mapToCategoryResponse(Category category) {
        long transactionCount = transactionRepository.countByCategoryId(category.getId());
        return CategoryResponse.builder()
                .id(category.getId())
                .name(category.getName())
                .color(category.getColor())
                .icon(category.getIcon())
                .isSystem(category.isSystem())
                .transactionCount(transactionCount)
                .build();
    }

    private User getCurrentUser() {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }
}
