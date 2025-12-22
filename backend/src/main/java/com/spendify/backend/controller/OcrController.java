package com.spendify.backend.controller;

import com.spendify.backend.dto.OcrResponse;
import com.spendify.backend.service.OcrService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/ocr")
@RequiredArgsConstructor
public class OcrController {

    private final OcrService ocrService;

    @PostMapping("/process")
    public ResponseEntity<OcrResponse> processReceipt(@RequestParam("file") MultipartFile file) {
        return ResponseEntity.ok(ocrService.processReceipt(file));
    }
}
