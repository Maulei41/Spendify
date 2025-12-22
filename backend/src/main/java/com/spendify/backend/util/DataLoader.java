package com.spendify.backend.util;

import com.spendify.backend.entity.Category;
import com.spendify.backend.repository.CategoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class DataLoader implements CommandLineRunner {

    private final CategoryRepository categoryRepository;

    @Override
    public void run(String... args) throws Exception {
        if (categoryRepository.count() == 0) {
            createDefaultCategories();
        }
    }

    private void createDefaultCategories() {
        List<Category> defaultCategories = List.of(
            Category.builder().name("Food & Dining").color("#2B5298").icon("🍽️").isSystem(true).displayOrder(1).build(),
            Category.builder().name("Transport").color("#52B788").icon("🚌").isSystem(true).displayOrder(2).build(),
            Category.builder().name("Shopping").color("#E63946").icon("🛍️").isSystem(true).displayOrder(3).build(),
            Category.builder().name("Entertainment").color("#9D4EDD").icon("🎬").isSystem(true).displayOrder(4).build(),
            Category.builder().name("Utilities").color("#7F8FA3").icon("💡").isSystem(true).displayOrder(5).build(),
            Category.builder().name("Other").color("#6C757D").icon("📌").isSystem(true).displayOrder(6).build()
        );
        categoryRepository.saveAll(defaultCategories);
    }
}
