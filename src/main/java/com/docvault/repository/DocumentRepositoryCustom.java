package com.docvault.repository;

import com.docvault.dto.StatsDto;
import com.docvault.model.Document;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.OffsetDateTime;
import java.util.List;

public interface DocumentRepositoryCustom {
    Page<Document> findAllFiltered(String tier, String department, Pageable pageable);
    StatsDto       getStats();
    void           updateLastAccessed(String id, OffsetDateTime at);
    void           patchRestoreStatus(String id, String status, OffsetDateTime at);
}
