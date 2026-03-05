package com.docvault.service;

import com.azure.storage.blob.*;
import com.azure.storage.blob.models.*;
import com.azure.storage.blob.options.*;
import com.azure.storage.blob.sas.*;
import com.azure.storage.blob.specialized.BlockBlobClient;
import com.azure.storage.common.StorageSharedKeyCredential;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.docvault.config.AzureStorageProperties;
import com.docvault.dto.BlobUploadResult;
import com.docvault.dto.SasResult;
import com.docvault.dto.RehydrationResult;
import com.docvault.exception.BlobNotFoundException;
import com.docvault.exception.BlobRehydrationException;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * Core Azure Blob Storage service.
 * Handles all blob operations: upload, SAS URL generation, tier management,
 * Archive rehydration, and deletion.
 *
 * Authentication priority:
 *   1. StorageSharedKeyCredential (account key in env)
 *   2. DefaultAzureCredential     (Managed Identity in production)
 */
@Service
public class BlobStorageService {

    private static final Logger log = LoggerFactory.getLogger(BlobStorageService.class);

    private final AzureStorageProperties props;
    private BlobServiceClient blobServiceClient;

    public BlobStorageService(AzureStorageProperties props) {
        this.props = props;
    }

    @PostConstruct
    public void init() {
        BlobServiceClientBuilder builder = new BlobServiceClientBuilder()
                .endpoint(props.getEndpoint());

        String key = props.getAccountKey();
        if (key != null && !key.isBlank()) {
            log.info("[Blob] Authenticating with StorageSharedKeyCredential");
            builder.credential(new StorageSharedKeyCredential(props.getAccountName(), key));
        } else {
            log.info("[Blob] Authenticating with DefaultAzureCredential (Managed Identity)");
            builder.credential(new DefaultAzureCredentialBuilder().build());
        }

        blobServiceClient = builder.buildClient();
        ensureContainersExist();
        log.info("[Blob] BlobServiceClient ready → {}", props.getEndpoint());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // UPLOAD
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Upload a file to the Hot container.
     *
     * @param file       Multipart file from HTTP request
     * @param docId      Pre-generated document UUID
     * @param title      Document title (stored as blob metadata)
     * @param author     Author name
     * @param department Department (partition context)
     * @return {@link BlobUploadResult} containing blobName, URL, etag
     */
    public BlobUploadResult uploadDocument(MultipartFile file, String docId,
                                           String title, String author, String department)
            throws IOException {

        String blobName = buildBlobName(department, docId, file.getOriginalFilename());
        String container = props.getContainer().getHot();

        log.info("[Blob] Uploading: docId={} blobName={} size={}",
                docId, blobName, file.getSize());

        BlobContainerClient containerClient = blobServiceClient.getBlobContainerClient(container);
        BlockBlobClient     blockBlobClient = containerClient.getBlobClient(blobName).getBlockBlobClient();

        BlobHttpHeaders headers = new BlobHttpHeaders()
                .setContentType(file.getContentType())
                .setContentDisposition("attachment; filename=\"" + file.getOriginalFilename() + "\"");

        Map<String, String> metadata = new HashMap<>();
        metadata.put("docId",      docId);
        metadata.put("title",      sanitiseMetaValue(title));
        metadata.put("author",     sanitiseMetaValue(author));
        metadata.put("department", sanitiseMetaValue(department));
        metadata.put("uploadedAt", OffsetDateTime.now().toString());

        blockBlobClient.uploadWithResponse(
                new BlockBlobSimpleUploadOptions(new ByteArrayInputStream(file.getBytes()), file.getSize())
                        .setHeaders(headers)
                        .setMetadata(metadata)
                        .setTier(AccessTier.HOT),
                null, null);

        BlobProperties blobProps = blockBlobClient.getProperties();

        return BlobUploadResult.builder()
                .blobName(blobName)
                .blobUrl(blockBlobClient.getBlobUrl())
                .containerName(container)
                .etag(blobProps.getETag())
                .storageTier("Hot")
                .build();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GENERATE SAS URL
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Generate a time-limited SAS read URL for Hot/Cool/Cold tier blobs.
     * Archive blobs must be rehydrated first.
     *
     * @return {@link SasResult} — status "ready" with sasUrl, or "rehydrating" without
     */
    public SasResult generateSasUrl(String containerName, String blobName) {
        BlobClient     blobClient = requireBlob(containerName, blobName);
        BlobProperties blobProps  = blobClient.getProperties();
        String         tier       = blobProps.getAccessTier() != null
                                    ? blobProps.getAccessTier().toString() : "Hot";

        if ("Archive".equalsIgnoreCase(tier)) {
            String archiveStatus = blobProps.getArchiveStatus() != null
                    ? blobProps.getArchiveStatus().toString() : null;
            if (archiveStatus != null && archiveStatus.startsWith("rehydrate-pending")) {
                return SasResult.rehydrating("Blob is being rehydrated from Archive.");
            }
            throw new BlobRehydrationException(
                    "Blob is in Archive tier. Call POST /rehydrate to initiate retrieval.");
        }

        int              expiry    = props.getSas().getExpiryMinutes();
        OffsetDateTime   expiresAt = OffsetDateTime.now().plusMinutes(expiry);

        BlobServiceSasSignatureValues sasValues = new BlobServiceSasSignatureValues(
                expiresAt, BlobSasPermission.parse("r"))
                .setStartTime(OffsetDateTime.now().minusMinutes(5));

        String sasToken = blobClient.generateSas(sasValues);
        String sasUrl   = blobClient.getBlobUrl() + "?" + sasToken;

        log.info("[Blob] SAS URL generated: blobName={} expiresAt={}", blobName, expiresAt);
        return SasResult.ready(sasUrl, expiry, expiresAt);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // REHYDRATION
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Initiate rehydration of an archived blob (Archive → Hot).
     *
     * @param containerName container
     * @param blobName      blob path
     * @param priority      "Standard" (1-15hr) or "High" (<1hr)
     */
    public RehydrationResult initiateRehydration(String containerName, String blobName, String priority) {
        BlobClient     blobClient = requireBlob(containerName, blobName);
        BlobProperties blobProps  = blobClient.getProperties();
        String         tier       = blobProps.getAccessTier() != null
                                    ? blobProps.getAccessTier().toString() : "";

        if (!"Archive".equalsIgnoreCase(tier)) {
            throw new BlobRehydrationException(
                    "Blob is not in Archive tier (current: " + tier + ").");
        }

        if (blobProps.getArchiveStatus() != null &&
            blobProps.getArchiveStatus().toString().startsWith("rehydrate-pending")) {
            return RehydrationResult.alreadyPending(priority);
        }

        RehydratePriority rehydratePriority = "High".equalsIgnoreCase(priority)
                ? RehydratePriority.HIGH : RehydratePriority.STANDARD;

        blobClient.setAccessTierWithResponse(
                new BlobSetAccessTierOptions(AccessTier.HOT).setPriority(rehydratePriority),
                null, null);

        OffsetDateTime estimatedReady = "High".equalsIgnoreCase(priority)
                ? OffsetDateTime.now().plusHours(1)
                : OffsetDateTime.now().plusHours(15);

        log.info("[Blob] Rehydration started: blobName={} priority={}", blobName, priority);
        return RehydrationResult.started(priority, estimatedReady);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SET TIER
    // ─────────────────────────────────────────────────────────────────────────

    public void setTier(String containerName, String blobName, String tier) {
        BlobClient blobClient = requireBlob(containerName, blobName);
        blobClient.setAccessTier(AccessTier.fromString(tier));
        log.info("[Blob] Tier changed: {} → {}", blobName, tier);
    }

    public String getCurrentTier(String containerName, String blobName) {
        BlobClient     client = requireBlob(containerName, blobName);
        BlobProperties props  = client.getProperties();
        return props.getAccessTier() != null ? props.getAccessTier().toString() : "Hot";
    }

    // ─────────────────────────────────────────────────────────────────────────
    // DOWNLOAD
    // ─────────────────────────────────────────────────────────────────────────

    public byte[] downloadBlob(String containerName, String blobName) {
        BlobClient blobClient = blobServiceClient
                .getBlobContainerClient(containerName)
                .getBlobClient(blobName);
        if (!blobClient.exists()) return new byte[0];
        try (ByteArrayOutputStream os = new ByteArrayOutputStream()) {
            blobClient.downloadStream(os);
            return os.toByteArray();
        } catch (Exception e) {
            log.warn("[Blob] Download failed for {}/{}: {}", containerName, blobName, e.getMessage());
            return new byte[0];
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // DELETE
    // ─────────────────────────────────────────────────────────────────────────

    public void deleteBlob(String containerName, String blobName) {
        BlobClient blobClient = requireBlob(containerName, blobName);
        blobClient.deleteIfExistsWithResponse(DeleteSnapshotsOptionType.INCLUDE, null, null, null);
        log.info("[Blob] Deleted: {}/{}", containerName, blobName);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HELPERS
    // ─────────────────────────────────────────────────────────────────────────

    private BlobClient requireBlob(String containerName, String blobName) {
        BlobClient client = blobServiceClient
                .getBlobContainerClient(containerName)
                .getBlobClient(blobName);
        if (!client.exists()) {
            throw new BlobNotFoundException("Blob not found: " + containerName + "/" + blobName);
        }
        return client;
    }

    private String buildBlobName(String department, String docId, String filename) {
        String safeDept = (department != null ? department : "General")
                .replaceAll("[^a-zA-Z0-9._\\-]", "_");
        String safeFile = (filename != null ? filename : "document")
                .replaceAll("[^a-zA-Z0-9._\\-]", "_");
        return safeDept + "/" + docId + "_" + safeFile;
    }

    private String sanitiseMetaValue(String value) {
        if (value == null) return "";
        return value.replaceAll("[\\r\\n]", " ").substring(0, Math.min(value.length(), 256));
    }

    private void ensureContainersExist() {
        for (String name : new String[]{ props.getContainer().getHot(), props.getContainer().getCold() }) {
            try {
                BlobContainerClient cc = blobServiceClient.getBlobContainerClient(name);
                boolean created = cc.createIfNotExists();
                if (created) {
                    log.info("[Blob] Created container: {}", name);
                } else {
                    log.info("[Blob] Container already exists: {}", name);
                }
            } catch (Exception e) {
                log.warn("[Blob] Could not verify/create container '{}': {} — continuing startup", name, e.getMessage());
            }
        }
    }
}
