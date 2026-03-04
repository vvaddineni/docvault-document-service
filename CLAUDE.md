# DocVault — Claude Code Context

## Architecture

```
Browser → nginx (docvault-frontend :80)
             → /api/* proxy → BFF (docvault-bff :4000)
                                → http://docvault-document-service  (Spring Boot :8080)
                                → http://docvault-search-service    (Spring Boot :8081)
                                        ↕
                               Azure Blob Storage (docvaultstor)
                               Azure Cosmos DB    (docvault-cosmosdb / DocVaultDB)
                               Azure AI Search    (docvault-search / index: documents)
```

All 4 services run in **Azure Container Apps** (ACA), resource group `docvault-rg`, environment `docvault-env` (FQDN suffix `niceisland-3b1d4e0d.eastus.azurecontainerapps.io`).

## Repositories

| Service | Repo | GitHub |
|---------|------|--------|
| Frontend (React + nginx) | `docvault-frontend/` | `vv4531/docvault-frontend` |
| BFF (Node.js/Express) | `docvault-bff/` | `vv4531/docvault-bff` |
| Document Service (Spring Boot) | `documentservice/docvault-document-service/` | `vv4531/docvault-document-service` |
| Search Service (Spring Boot) | `docvault-search-service/` | `vv4531/docvault-search-service` |

## ACA Internal Hostnames

Services communicate via ACA internal DNS — **always use these, never external FQDNs**:

| Service | Internal URL |
|---------|-------------|
| Document Service | `http://docvault-document-service` |
| Search Service | `http://docvault-search-service` |

The BFF cannot use internal DNS for itself (nginx proxy must use the external HTTPS FQDN — see nginx.conf).

## Environment Variables

### BFF (`docvault-bff`)
```
DOCUMENT_SERVICE_URL=http://docvault-document-service
SEARCH_SERVICE_URL=http://docvault-search-service
FRONTEND_URL=https://docvault-frontend.niceisland-3b1d4e0d.eastus.azurecontainerapps.io
```

### Document Service (`docvault-document-service`)
```
AZURE_STORAGE_ACCOUNT_NAME=docvaultstor
AZURE_STORAGE_ACCOUNT_KEY=<key>
AZURE_COSMOS_ENDPOINT=https://docvault-cosmosdb.documents.azure.com:443/
AZURE_COSMOS_KEY=<key>
SEARCH_SERVICE_URL=http://docvault-search-service
```

### Search Service (`docvault-search-service`)
```
AZURE_SEARCH_ENDPOINT=https://docvault-search.search.windows.net
AZURE_SEARCH_API_KEY=<key>
```

## GitHub Org Secrets (required for CI)

Set these at the org level so all repo CIs can use them:

```
AZURE_CREDENTIALS          # Service principal JSON for az login
AZURE_RESOURCE_GROUP       # docvault-rg
GHCR_TOKEN                 # GitHub PAT for container registry
AZURE_STORAGE_ACCOUNT_NAME # docvaultstor
AZURE_STORAGE_ACCOUNT_KEY  # Storage account key
AZURE_COSMOS_ENDPOINT      # https://docvault-cosmosdb.documents.azure.com:443/
AZURE_COSMOS_KEY            # Cosmos DB key
AZURE_SEARCH_ENDPOINT      # https://docvault-search.search.windows.net
AZURE_SEARCH_API_KEY        # Azure AI Search admin key
DOCUMENT_SERVICE_URL        # http://docvault-document-service
SEARCH_SERVICE_URL          # http://docvault-search-service
```

## Key Files

### Frontend
- `docvault-frontend/nginx.conf` — SPA + `/api` reverse proxy to BFF external HTTPS FQDN
  - Uses `resolver 8.8.8.8` (public DNS, required in ACA — `127.0.0.11` doesn't work)
  - Uses `set $bff_upstream https://...` variable + `proxy_ssl_server_name on` for dynamic HTTPS proxy

### BFF
- `docvault-bff/src/server.js` — Express app; `app.set('trust proxy', 1)` is required for ACA (load balancer sets X-Forwarded-For)
- `docvault-bff/src/services/apimClient.js` — Routes `/documents/v1/*` → docClient, `/search/v1/*` → searchClient
- `docvault-bff/src/routes/documents.js` — Converts 1-based frontend page to 0-based Spring Pageable (`page: page - 1`)

### Document Service
- `src/main/java/com/docvault/service/DocumentService.java` — Upload pipeline + async AI Search indexing
- `src/main/java/com/docvault/service/BlobStorageService.java` — Azure Blob operations; upload uses `ByteArrayInputStream` (required for Azure SDK mark/reset)
- `src/main/java/com/docvault/repository/DocumentRepositoryCustomImpl.java` — Raw Cosmos DB queries

### Search Service
- `src/main/java/com/docvault/service/AzureSearchService.java` — Creates index on startup, full-text search + facets

## Common Operations

### Re-index all documents into Azure AI Search
```bash
curl -X POST "https://docvault-bff.niceisland-3b1d4e0d.eastus.azurecontainerapps.io/api/documents/reindex"
```
Run this after any search service outage or if search returns empty results.

### Fix broken env vars on BFF (after bad CI deploy)
```bash
az containerapp update \
  --name docvault-bff \
  --resource-group docvault-rg \
  --set-env-vars \
    DOCUMENT_SERVICE_URL="http://docvault-document-service" \
    SEARCH_SERVICE_URL="http://docvault-search-service"
```

### Check env vars on a container
```bash
az containerapp show \
  --name docvault-bff \
  --resource-group docvault-rg \
  --query "properties.template.containers[0].env"
```

### Stream logs
```bash
az containerapp logs show --name docvault-bff --resource-group docvault-rg --follow
az containerapp logs show --name docvault-document-service --resource-group docvault-rg --follow
az containerapp logs show --name docvault-search-service --resource-group docvault-rg --follow
```

## Known Issues & Fixes Applied

| Issue | Root Cause | Fix |
|-------|-----------|-----|
| `mark/reset not supported` on upload | Azure SDK retries require seekable stream | Use `ByteArrayInputStream(file.getBytes())` |
| `ERR_ERL_UNEXPECTED_X_FORWARDED_FOR` | ACA load balancer sets X-Forwarded-For but Express trust proxy not set | `app.set('trust proxy', 1)` in server.js |
| `415 Unsupported Media Type` on search index POST | RestTemplate sends `Map` as `application/x-www-form-urlencoded` by default | Wrap in `HttpEntity` with `Content-Type: application/json` header |
| Dashboard list empty | Frontend sends 1-based page, Spring Pageable is 0-based | BFF converts: `page: page - 1` |
| CI wipes env vars | GitHub secrets empty → `az containerapp update` sets empty values | Guard condition: only update if secrets are non-empty |
| nginx 502 to BFF | BFF has `allowInsecure: false` (HTTPS only), nginx was using HTTP | nginx must proxy to external HTTPS FQDN |
| Search returns 0 results after reindex | `RestTemplate.postForEntity` with Map sends wrong Content-Type | Fixed with HttpEntity + explicit Content-Type |
| `CredentialUnavailableException` at startup | Empty Azure credential env vars from CI | Set via Cloud Shell, add guard to CI |

## CI/CD Notes

- Each service has its own GitHub Actions workflow in `.github/workflows/ci.yml`
- All CIs have guards: only update env vars if GitHub secrets are non-empty (prevents wiping manual values)
- BFF CI guard: `if [ -n "$DOC_URL" ] && [ -n "$SRCH_URL" ]`
- Document service CI guard: `if [ -n "$STORAGE_ACCOUNT" ] && [ -n "$STORAGE_KEY" ] && ...`
- Search service CI guard: `if [ -n "$SRCH_ENDPOINT" ] && [ -n "$SRCH_KEY" ]`
- `azure/container-apps-deploy-action` preserves existing env vars when only updating image

## Azure Resources

| Resource | Name |
|----------|------|
| Resource Group | `docvault-rg` |
| ACA Environment | `docvault-env` |
| Storage Account | `docvaultstor` |
| Blob Containers | `documents-hot`, `documents-cold` |
| Cosmos DB Account | `docvault-cosmosdb` |
| Cosmos DB / Container | `DocVaultDB` / `documents` |
| AI Search Service | `docvault-search` |
| AI Search Index | `documents` |
