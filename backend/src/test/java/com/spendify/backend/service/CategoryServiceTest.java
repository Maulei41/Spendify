package com.spendify.backend.service;

import com.spendify.backend.dto.CategoryResponse;
import com.spendify.backend.dto.CreateCategoryRequest;
import com.spendify.backend.entity.Category;
import com.spendify.backend.entity.User;
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

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CategoryServiceTest {

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
    private CategoryService categoryService;

    private User testUser;

    @BeforeEach
    void setUp() {
        // Mock the security context to simulate an authenticated user
        when(securityContext.getAuthentication()).thenReturn(authentication);
        SecurityContextHolder.setContext(securityContext);

        testUser = new User();
        testUser.setId(1L);
        testUser.setEmail("test@example.com");
    }

    @Test
    void createCategory_whenDetailsAreValid_shouldCreateAndReturnCategory() {
        // Given: A valid request and an authenticated user
        CreateCategoryRequest request = CreateCategoryRequest.builder()
                .name("New Category")
                .color("#FF5733")
                .icon("🚀")
                .build();

        when(authentication.getName()).thenReturn(testUser.getEmail());
        when(userRepository.findByEmail(testUser.getEmail())).thenReturn(Optional.of(testUser));
        when(categoryRepository.findByNameAndUserId(request.getName(), testUser.getId())).thenReturn(Optional.empty());

        Category savedCategory = Category.builder()
                .id(100L)
                .name(request.getName())
                .color(request.getColor())
                .icon(request.getIcon())
                .user(testUser)
                .isSystem(false)
                .build();
        when(categoryRepository.save(any(Category.class))).thenReturn(savedCategory);
        when(transactionRepository.countByCategoryId(savedCategory.getId())).thenReturn(0L);


        // When: The createCategory method is called
        CategoryResponse response = categoryService.createCategory(request);

        // Then: The service should save a new category and return the correct DTO
        assertThat(response).isNotNull();
        assertThat(response.getId()).isEqualTo(savedCategory.getId());
        assertThat(response.getName()).isEqualTo(request.getName());
        assertThat(response.isSystem()).isFalse();
        assertThat(response.getTransactionCount()).isZero();

        // Verify that the repository's save method was called exactly once with the correct user
        verify(categoryRepository).save(argThat(category ->
                category.getName().equals(request.getName()) &&
                category.getUser().getId().equals(testUser.getId()) &&
                !category.isSystem()
        ));
    }

    @Test
    void createCategory_whenNameAlreadyExists_shouldThrowException() {
        // Given: A request with a name that already exists for the user
        CreateCategoryRequest request = new CreateCategoryRequest("Existing Category", "#123456", "🤔");
        Category existingCategory = new Category(200L, testUser, "Existing Category", "#123456", "🤔", false, 1);

        when(authentication.getName()).thenReturn(testUser.getEmail());
        when(userRepository.findByEmail(testUser.getEmail())).thenReturn(Optional.of(testUser));
        when(categoryRepository.findByNameAndUserId(request.getName(), testUser.getId())).thenReturn(Optional.of(existingCategory));

        // When & Then: Calling createCategory should throw a DuplicateCategoryException
        assertThatThrownBy(() -> categoryService.createCategory(request))
                .isInstanceOf(DuplicateCategoryException.class)
                .hasMessage("Category with this name already exists.");

        // Verify that the save method was never called
        verify(categoryRepository, never()).save(any(Category.class));
    }
}