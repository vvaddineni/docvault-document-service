# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Repository Layout

This is a multi-repo project. The four services live in sibling directories:

```
docvault/
├── docvault-frontend/              # React SPA (port 3000)
├── docvault-bff/                   # Node.js BFF (port 4000)
├── documentservice/
│   └── docvault-document-service/  # Spring Boot (this repo, port 8080)
└── docvault-search-service/        # Spring Boot (port 8081)
```

## Commands

```bash
mvn test                            # run all tests
mvn test -Dtest=DocumentServiceTest # run a single test class
mvn package -DskipTests             # build JAR → target/*.jar
mvn spring-boot:run                 # run locally (requires Azure env vars below)
```

## Architecture

### Request Flow
```
Browser → React SPA (3000)
       → BFF /api/documents (4000)
         → Document Service /v1/documents (8080)
           → Azure Blob Storage (Hot/Cool/Archive)
           → Azure Cosmos DB (metadata)
           → Search Service /v1/search/index (8081) [async]
```

### Source Layout
```
src/main/java/com/docvault/
├── controller/    DocumentController.java   @RequestMapping("/v1/documents")
├── service/       DocumentService.java, BlobStorageService.java, TextExtractionService.java
├── repository/    DocumentRepository + custom impl (Spring Data Cosmos)
├── model/         Document.java (Cosmos entity, partition key = /department)
├── dto/           12 DTOs (DocumentDto, UploadMetadataDto, etc.)
├── config/        AzureStorageProperties.java
└── exception/     Custom exceptions
```

### API Endpoints
| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/v1/documents` | Paginated list; query params: `tier`, `department`, `page`, `size` |
| `GET` | `/v1/documents/stats` | Storage aggregation (count, size by tier) |
| `GET` | `/v1/documents/{id}` | Document metadata |
| `POST` | `/v1/documents` | Upload (multipart: `file` + `metadata`) |
| `GET` | `/v1/documents/{id}/download` | Generate SAS URL (or start rehydration for Archive) |
| `POST` | `/v1/documents/{id}/rehydrate` | Trigger Archive-tier rehydration |
| `PATCH` | `/v1/documents/{id}` | Update metadata |
| `DELETE` | `/v1/documents/{id}` | Delete document + blob |

Swagger UI: `http://localhost:8080/swagger-ui.html`

## Key Patterns & Gotchas

### File Upload Ordering (critical)
Call `file.getBytes()` **before** `blobService.uploadDocument()`. The blob upload consumes `getInputStream()`, so a subsequent `getBytes()` returns empty bytes.

```java
byte[] fileBytes = file.getBytes();   // must come first
blobStorageService.uploadDocument(fileBytes, ...);
```

### Async Indexing
After Cosmos DB save, text extraction + search indexing run asynchronously:
```java
CompletableFuture.runAsync(() -> {
    String text = textExtractionService.extract(fileBytes, contentType);
    // POST to SEARCH_SERVICE_URL/v1/search/index
});
```
The `POST /v1/documents` endpoint returns immediately without waiting for indexing.

### Apache Tika 2.x
This project uses Tika 2.9.1. The API changed — use `meta.add(...)` not the removed `Metadata.CONTENT_TYPE` constant:
```java
Metadata meta = new Metadata();
meta.add("Content-Type", contentType);   // correct for Tika 2.x
```

### Upload Controller Signature
```java
@PostMapping
public ResponseEntity<DocumentDto> uploadDocument(
    @RequestPart("file") MultipartFile file,
    @RequestPart("metadata") @Valid UploadMetadataDto metadata
)
```
The `metadata` part must be sent with `Content-Type: application/json`.

### Cosmos DB
- Database: `DocVaultDB`, Container: `documents`, Partition key: `/department`
- Uses `spring-cloud-azure-starter-data-cosmos` v5.9.1
- Spring Data repository: `DocumentRepository extends CosmosRepository<Document, String>`

### Blob Storage Tiers
- Hot → `documents-hot` container
- Cool/Archive → `documents-cold` container
- SAS URL expiry: 15 minutes (configurable via `AZURE_STORAGE_SAS_EXPIRY_MINUTES`)
- Rehydration priority: Standard (configurable)

## Configuration

### application.yml highlights
- Server port: **8080**
- Max file upload: **100MB** (`spring.servlet.multipart.max-file-size`)
- Actuator endpoints: `/actuator/health`, `/actuator/metrics`, `/actuator/prometheus`
- Logging: DEBUG for `com.docvault`, WARN for Azure SDK

### Required Environment Variables
| Variable | Default | Description |
|----------|---------|-------------|
| `AZURE_COSMOS_ENDPOINT` | `https://docvaultdev.documents.azure.com:443/` | Cosmos DB endpoint |
| `AZURE_COSMOS_KEY` | — | Cosmos DB primary key |
| `AZURE_COSMOS_DATABASE` | `DocVaultDB` | Database name |
| `AZURE_STORAGE_ACCOUNT_NAME` | `docvaultdev` | Blob storage account |
| `AZURE_STORAGE_ACCOUNT_KEY` | — | Storage key (blank = Managed Identity) |
| `AZURE_STORAGE_ENDPOINT` | — | Blob endpoint URL |
| `AZURE_STORAGE_CONTAINER_HOT` | — | Hot-tier container name |
| `AZURE_STORAGE_CONTAINER_COLD` | — | Cold/Archive-tier container name |
| `AZURE_CLIENT_ID` | — | Azure AD client ID (for Managed Identity) |
| `AZURE_TENANT_ID` | — | Azure AD tenant ID |
| `AZURE_CLIENT_SECRET` | — | Azure AD client secret |
| `SEARCH_SERVICE_URL` | `http://localhost:8081` | Search Service base URL |

## CI/CD

GitHub Actions (`.github/workflows/ci.yml`):
1. `mvn test` — must pass
2. `mvn package -DskipTests` — build JAR
3. Build + push Docker image → `ghcr.io/<owner>/docvault-document-service:latest` and `:<sha>`
4. Deploy to Azure Container App: `docvault-document-service` in `docvault-dev-rg`

**Required GitHub Secrets**: `AZURE_CREDENTIALS`, `AZURE_RESOURCE_GROUP`, `GHCR_TOKEN`, `SEARCH_SERVICE_URL`

## Docker

Multi-stage build: Maven 3.9 builder → Eclipse Temurin 21 JRE runtime.
- Non-root user: `spring`
- JVM flags: `-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0`
- Healthcheck: `GET /actuator/health` (30s interval, 5s timeout, 40s start period)
- Exposes port **8080**
