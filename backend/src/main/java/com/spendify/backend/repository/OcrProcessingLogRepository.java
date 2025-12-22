package com.spendify.backend.repository;

import com.spendify.backend.entity.OcrProcessingLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface OcrProcessingLogRepository extends JpaRepository<OcrProcessingLog, Long> {
}
