package com.docvault.repository;

import com.azure.spring.data.cosmos.repository.CosmosRepository;
import com.docvault.model.Document;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

/**
 * Cosmos DB repository for Document metadata.
 *
 * Spring Data Cosmos handles basic CRUD. Custom query methods are
 * implemented in DocumentRepositoryCustom.
 */
@Repository
public interface DocumentRepository
        extends CosmosRepository<Document, String>, DocumentRepositoryCustom {

    Page<Document> findByDepartment(String department, Pageable pageable);

    Page<Document> findByStorageTier(String tier, Pageable pageable);

    Page<Document> findByDepartmentAndStorageTier(String dept, String tier, Pageable pageable);
}
