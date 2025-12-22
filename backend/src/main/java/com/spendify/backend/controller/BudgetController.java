package com.spendify.backend.controller;

import com.spendify.backend.dto.BudgetResponse;
import com.spendify.backend.dto.CreateBudgetRequest;
import com.spendify.backend.dto.UpdateBudgetRequest;
import com.spendify.backend.service.BudgetService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/budget")
@RequiredArgsConstructor
public class BudgetController {

    private final BudgetService budgetService;

    @PostMapping
    public ResponseEntity<BudgetResponse> createBudget(@Valid @RequestBody CreateBudgetRequest request) {
        return new ResponseEntity<>(budgetService.createBudget(request), HttpStatus.CREATED);
    }

    @GetMapping("/current-month")
    public ResponseEntity<BudgetResponse> getCurrentMonthBudget() {
        return ResponseEntity.ok(budgetService.getCurrentMonthBudget());
    }

    @GetMapping("/history")
    public ResponseEntity<Page<BudgetResponse>> getBudgetHistory(Pageable pageable) {
        return ResponseEntity.ok(budgetService.getBudgetHistory(pageable));
    }

    @PutMapping("/{id}")
    public ResponseEntity<BudgetResponse> updateBudget(@PathVariable Long id, @Valid @RequestBody UpdateBudgetRequest request) {
        return ResponseEntity.ok(budgetService.updateBudget(id, request));
    }
}
