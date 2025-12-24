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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
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
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Category with this name already exists.");

        // Verify that the save method was never called
        verify(categoryRepository, never()).save(any(Category.class));
    }

    @Test
    void getAllCategories_shouldReturnUserAndSystemCategories() {
        // Given
        User testUser = new User();
        testUser.setId(1L);
        when(authentication.getName()).thenReturn("test@example.com");
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(testUser));

        Category userCategory = Category.builder().id(101L).name("Personal").user(testUser).isSystem(false).build();
        Category systemCategory = Category.builder().id(201L).name("Food").user(null).isSystem(true).build();

        when(categoryRepository.findByUserIdOrIsSystem(1L, true)).thenReturn(List.of(userCategory, systemCategory));
        when(transactionRepository.countByCategoryId(anyLong())).thenReturn(0L); // Assume 0 for simplicity

        // When
        List<CategoryResponse> categories = categoryService.getAllCategories();

        // Then
        assertThat(categories).hasSize(2);
        assertThat(categories).extracting(CategoryResponse::getName).contains("Personal", "Food");
    }

    @Test
    void updateCategory_whenValid_shouldUpdateAndReturnCategory() {
        // Given
        User testUser = new User();
        testUser.setId(1L);
        when(authentication.getName()).thenReturn("test@example.com");
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(testUser));

        Long categoryId = 101L;
        Category existingCategory = Category.builder().id(categoryId).name("Old Name").user(testUser).isSystem(false).build();
        UpdateCategoryRequest request = UpdateCategoryRequest.builder().name("New Name").build();

        when(categoryRepository.findById(categoryId)).thenReturn(Optional.of(existingCategory));
        when(categoryRepository.save(any(Category.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(transactionRepository.countByCategoryId(categoryId)).thenReturn(5L);

        // When
        CategoryResponse response = categoryService.updateCategory(categoryId, request);

        // Then
        assertThat(response.getName()).isEqualTo("New Name");
        assertThat(response.getTransactionCount()).isEqualTo(5L);
        verify(categoryRepository).save(argThat(cat -> cat.getName().equals("New Name")));
    }

    @Test
    void updateCategory_whenCategoryNotFound_shouldThrowException() {
        // Given
        User testUser = new User();
        testUser.setId(1L);
        when(authentication.getName()).thenReturn("test@example.com");
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(testUser));

        Long categoryId = 999L;
        when(categoryRepository.findById(categoryId)).thenReturn(Optional.empty());

        // When & Then
        assertThatThrownBy(() -> categoryService.updateCategory(categoryId, new UpdateCategoryRequest()))
                .isInstanceOf(ResourceNotFoundException.class);
    }
    
    @Test
    void updateCategory_whenUserNotAuthorized_shouldThrowException() {
        // Given
        User currentUser = new User();
        currentUser.setId(1L);
        User anotherUser = new User();
        anotherUser.setId(2L);
        when(authentication.getName()).thenReturn("test@example.com");
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(currentUser));

        Long categoryId = 102L;
        Category anotherUsersCategory = Category.builder().id(categoryId).name("Another User's").user(anotherUser).isSystem(false).build();

        when(categoryRepository.findById(categoryId)).thenReturn(Optional.of(anotherUsersCategory));

        // When & Then
        assertThatThrownBy(() -> categoryService.updateCategory(categoryId, new UpdateCategoryRequest()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("User not authorized to update this category.");
    }
    
    @Test
    void updateCategory_whenUpdatingSystemCategory_shouldThrowException() {
        // Given
        User currentUser = new User();
        currentUser.setId(1L);
        when(authentication.getName()).thenReturn("test@example.com");
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(currentUser));

        Long categoryId = 201L;
        Category systemCategory = Category.builder().id(categoryId).name("System Cat").user(null).isSystem(true).build();

        when(categoryRepository.findById(categoryId)).thenReturn(Optional.of(systemCategory));

        // When & Then
        assertThatThrownBy(() -> categoryService.updateCategory(categoryId, new UpdateCategoryRequest()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("User not authorized to update this category.");
    }

    @Test
    void deleteCategory_withReassignId_shouldReassignTransactionsAndDelete() {
        // Given
        User currentUser = new User();
        currentUser.setId(1L);
        when(authentication.getName()).thenReturn("test@example.com");
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(currentUser));

        Long categoryToDeleteId = 101L;
        Long reassignCategoryId = 102L;
        Category categoryToDelete = Category.builder().id(categoryToDeleteId).name("To Delete").user(currentUser).isSystem(false).build();
        Category reassignCategory = Category.builder().id(reassignCategoryId).name("Reassign Here").user(currentUser).isSystem(false).build();

        when(categoryRepository.findById(categoryToDeleteId)).thenReturn(Optional.of(categoryToDelete));
        when(categoryRepository.findById(reassignCategoryId)).thenReturn(Optional.of(reassignCategory));

        // When
        categoryService.deleteCategory(categoryToDeleteId, reassignCategoryId);

        // Then
        verify(transactionRepository).reassignCategory(currentUser.getId(), categoryToDeleteId, reassignCategoryId);
        verify(categoryRepository).delete(categoryToDelete);
    }
}