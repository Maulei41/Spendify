package com.spendify.backend.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class CreateCategoryRequest {

    @NotEmpty(message = "Name is required")
    @Size(max = 50, message = "Name cannot exceed 50 characters")
    private String name;

    @NotEmpty(message = "Color is required")
    private String color;

    @NotEmpty(message = "Icon is required")
    @Size(max = 2, message = "Icon cannot exceed 2 characters")
    private String icon;
}
