package com.docvault.service;

import com.docvault.dto.*;
import com.docvault.exception.DocumentNotFoundException;
import com.docvault.model.Document;
import com.docvault.repository.DocumentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Orchestrates the full document lifecycle:
 *   upload → blob store → Cosmos metadata → (async) text extract → AI Search index
 */
@Service
public class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);

    @Value("${search.service.url:}")
    private String searchServiceUrl;

    private final BlobStorageService    blobService;
    private final DocumentRepository    repository;
    private final TextExtractionService textExtractionService;
    private final RestTemplate          restTemplate = new RestTemplate();

    public DocumentService(BlobStorageService blobService,
                           DocumentRepository repository,
                           TextExtractionService textExtractionService) {
        this.blobService           = blobService;
        this.repository            = repository;
        this.textExtractionService = textExtractionService;
    }

    // ── Upload ────────────────────────────────────────────────────────────
    public DocumentDto upload(MultipartFile file, UploadMetadataDto meta) throws IOException {
        String docId = UUID.randomUUID().toString();

        // 1. Upload binary to Azure Blob Storage (Hot tier)
        BlobUploadResult blob = blobService.uploadDocument(
                file, docId,
                meta.getTitle() != null ? meta.getTitle() : file.getOriginalFilename(),
                meta.getAuthor(),
                meta.getDepartment() != null ? meta.getDepartment() : "General");

        // 2. Build and persist metadata record (Cosmos DB)
        Document doc = new Document();
        doc.setId(docId);
        doc.setTitle(meta.getTitle() != null ? meta.getTitle() : file.getOriginalFilename());
        doc.setAuthor(meta.getAuthor() != null ? meta.getAuthor() : "Unknown");
        doc.setDepartment(meta.getDepartment() != null ? meta.getDepartment() : "General");
        doc.setTags(meta.getTags() != null ? meta.getTags() : List.of());
        doc.setDescription(meta.getDescription());
        doc.setFilename(file.getOriginalFilename());
        doc.setMimeType(file.getContentType());
        doc.setFileSizeBytes(file.getSize());
        doc.setBlobName(blob.getBlobName());
        doc.setBlobUrl(blob.getBlobUrl());
        doc.setContainerName(blob.getContainerName());
        doc.setEtag(blob.getEtag());
        doc.setStorageTier("Hot");
        doc.setUploadedAt(OffsetDateTime.now());
        doc.setLastAccessedAt(OffsetDateTime.now());

        Document saved = repository.save(doc);
        log.info("[DocumentService] Uploaded: docId={} tier=Hot", docId);

        // 3. Extract text and index in AI Search — fire-and-forget (non-blocking)
        // Read file bytes now (MultipartFile stream may not be re-readable after request ends)
        byte[] fileBytes;
        try { fileBytes = file.getBytes(); } catch (Exception e) { fileBytes = new byte[0]; }
        final byte[] bytes = fileBytes;
        final DocumentDto dto = toDto(saved);

        CompletableFuture.runAsync(() -> {
            String extractedText = "";
            try {
                extractedText = textExtractionService.extractFromBytes(bytes, file.getOriginalFilename(), file.getContentType());
            } catch (Exception e) {
                log.warn("[DocumentService] Text extraction failed for {}: {}", docId, e.getMessage());
            }
            indexInSearch(dto, extractedText);
        });

        return dto;
    }

    private void indexInSearch(DocumentDto doc, String extractedText) {
        if (searchServiceUrl == null || searchServiceUrl.isBlank()) return;
        try {
            Map<String, Object> indexDoc = new HashMap<>();
            indexDoc.put("id",            doc.getId());
            indexDoc.put("title",         doc.getTitle());
            indexDoc.put("author",        doc.getAuthor());
            indexDoc.put("department",    doc.getDepartment());
            indexDoc.put("description",   doc.getDescription());
            indexDoc.put("tags",          doc.getTags());
            indexDoc.put("mimeType",      doc.getMimeType());
            indexDoc.put("storageTier",   doc.getStorageTier());
            indexDoc.put("fileSizeBytes", doc.getFileSizeBytes());
            indexDoc.put("uploadedAt",    doc.getUploadedAt() != null ? doc.getUploadedAt().toString() : null);
            indexDoc.put("extractedText", extractedText);
            restTemplate.postForEntity(searchServiceUrl + "/v1/search/index", indexDoc, Void.class);
            log.info("[DocumentService] Indexed docId={} in AI Search", doc.getId());
        } catch (Exception e) {
            log.warn("[DocumentService] Search indexing failed for {}: {}", doc.getId(), e.getMessage());
        }
    }

    // ── List ──────────────────────────────────────────────────────────────
    public Page<DocumentDto> list(String tier, String department, Pageable pageable) {
        return repository.findAllFiltered(tier, department, pageable).map(this::toDto);
    }

    // ── Get by ID ─────────────────────────────────────────────────────────
    public DocumentDto getById(String id) {
        Document doc = repository.findById(id)
                .orElseThrow(() -> new DocumentNotFoundException("Document not found: " + id));
        // Sync tier from blob (in case lifecycle policy changed it)
        try {
            String actualTier = blobService.getCurrentTier(doc.getContainerName(), doc.getBlobName());
            if (!actualTier.equals(doc.getStorageTier())) {
                doc.setStorageTier(actualTier);
                repository.save(doc);
            }
        } catch (Exception e) {
            log.warn("[DocumentService] Could not sync tier for {}: {}", id, e.getMessage());
        }
        repository.updateLastAccessed(id, OffsetDateTime.now());
        return toDto(doc);
    }

    // ── Download (SAS URL) ────────────────────────────────────────────────
    public DownloadResponseDto getDownloadUrl(String id, String priority) {
        Document doc = repository.findById(id)
                .orElseThrow(() -> new DocumentNotFoundException("Document not found: " + id));

        if ("Archive".equalsIgnoreCase(doc.getStorageTier())) {
            // Check rehydration status directly from blob
            SasResult sasResult = blobService.generateSasUrl(doc.getContainerName(), doc.getBlobName());
            if ("rehydrating".equals(sasResult.getStatus())) {
                return DownloadResponseDto.rehydrating(sasResult.getMessage());
            }
        }

        SasResult result = blobService.generateSasUrl(doc.getContainerName(), doc.getBlobName());
        repository.updateLastAccessed(id, OffsetDateTime.now());
        return DownloadResponseDto.ready(result.getSasUrl(), result.getExpiryMinutes(), result.getExpiresAt());
    }

    // ── Rehydrate ─────────────────────────────────────────────────────────
    public RehydrationResponseDto rehydrate(String id, String priority) {
        Document doc = repository.findById(id)
                .orElseThrow(() -> new DocumentNotFoundException("Document not found: " + id));

        RehydrationResult result = blobService.initiateRehydration(
                doc.getContainerName(), doc.getBlobName(), priority);

        repository.patchRestoreStatus(id, "rehydrating", OffsetDateTime.now());

        return RehydrationResponseDto.builder()
                .status(result.getStatus())
                .priority(result.getPriority())
                .message(result.getMessage())
                .estimatedReadyAt(result.getEstimatedReadyAt())
                .build();
    }

    // ── Update ────────────────────────────────────────────────────────────
    public DocumentDto update(String id, UpdateDocumentDto dto) {
        Document doc = repository.findById(id)
                .orElseThrow(() -> new DocumentNotFoundException("Document not found: " + id));

        if (dto.getTitle()       != null) doc.setTitle(dto.getTitle());
        if (dto.getAuthor()      != null) doc.setAuthor(dto.getAuthor());
        if (dto.getDepartment()  != null) doc.setDepartment(dto.getDepartment());
        if (dto.getTags()        != null) doc.setTags(dto.getTags());
        if (dto.getDescription() != null) doc.setDescription(dto.getDescription());

        return toDto(repository.save(doc));
    }

    // ── Delete ────────────────────────────────────────────────────────────
    public void delete(String id) {
        Document doc = repository.findById(id)
                .orElseThrow(() -> new DocumentNotFoundException("Document not found: " + id));

        blobService.deleteBlob(doc.getContainerName(), doc.getBlobName());
        repository.deleteById(id);
        // searchIndexingService.delete(id);
        log.info("[DocumentService] Deleted: docId={}", id);
    }

    // ── Stats ─────────────────────────────────────────────────────────────
    public StatsDto getStats() {
        return repository.getStats();
    }

    // ── Mapping ───────────────────────────────────────────────────────────
    private DocumentDto toDto(Document doc) {
        DocumentDto dto = new DocumentDto();
        dto.setId(doc.getId());
        dto.setTitle(doc.getTitle());
        dto.setAuthor(doc.getAuthor());
        dto.setDepartment(doc.getDepartment());
        dto.setTags(doc.getTags());
        dto.setDescription(doc.getDescription());
        dto.setFilename(doc.getFilename());
        dto.setMimeType(doc.getMimeType());
        dto.setFileSizeBytes(doc.getFileSizeBytes());
        dto.setBlobName(doc.getBlobName());
        dto.setStorageTier(doc.getStorageTier());
        dto.setUploadedAt(doc.getUploadedAt());
        dto.setLastAccessedAt(doc.getLastAccessedAt());
        dto.setArchivedAt(doc.getArchivedAt());
        dto.setRestoreStatus(doc.getRestoreStatus());
        return dto;
    }
}
