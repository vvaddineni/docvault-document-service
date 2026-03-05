package com.docvault.repository;

import com.azure.cosmos.CosmosAsyncClient;
import com.azure.cosmos.CosmosAsyncContainer;
import com.azure.cosmos.models.*;
import com.docvault.dto.StatsDto;
import com.docvault.model.Document;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.*;

@Component
public class DocumentRepositoryCustomImpl implements DocumentRepositoryCustom {

    private final CosmosAsyncClient cosmosClient;
    private final String            database;
    private final String            containerName;

    public DocumentRepositoryCustomImpl(
            @Qualifier("cosmosAsyncClient") CosmosAsyncClient cosmosClient,
            @Value("${tier-migration.cosmos.database:DocVaultDB}") String database,
            @Value("${tier-migration.cosmos.container:documents}") String containerName) {
        this.cosmosClient  = cosmosClient;
        this.database      = database;
        this.containerName = containerName;
    }

    private CosmosAsyncContainer container() {
        return cosmosClient.getDatabase(database).getContainer(containerName);
    }

    @Override
    public Page<Document> findAllFiltered(String tier, String department, Pageable pageable) {
        StringBuilder sql = new StringBuilder("SELECT * FROM c");
        List<SqlParameter> params = new ArrayList<>();

        if (tier != null || department != null) {
            sql.append(" WHERE");
            if (tier != null) {
                sql.append(" c.storageTier = @tier");
                params.add(new SqlParameter("@tier", tier));
            }
            if (department != null) {
                if (tier != null) sql.append(" AND");
                sql.append(" c.department = @dept");
                params.add(new SqlParameter("@dept", department));
            }
        }

        sql.append(" ORDER BY c.uploadedAt DESC OFFSET ")
           .append(pageable.getOffset()).append(" LIMIT ").append(pageable.getPageSize());

        SqlQuerySpec spec = new SqlQuerySpec(sql.toString(), params);
        CosmosQueryRequestOptions opts = new CosmosQueryRequestOptions();

        List<Document> items = container()
                .queryItems(spec, opts, Document.class)
                .collectList()
                .block();

        // Count query
        String countSql = sql.toString()
                .replaceFirst("SELECT \\*", "SELECT VALUE COUNT(1)")
                .replaceFirst("ORDER BY.*", "");
        Long total = container()
                .queryItems(new SqlQuerySpec(countSql, params), opts, Long.class)
                .blockFirst();

        return new PageImpl<>(items != null ? items : List.of(), pageable,
                              total != null ? total : 0);
    }

    @Override
    public StatsDto getStats() {
        CosmosQueryRequestOptions opts = new CosmosQueryRequestOptions();

        // 1. Tier aggregation (total count + bytes per tier)
        String tierSql = "SELECT c.storageTier, COUNT(1) as cnt, SUM(c.fileSizeBytes) as bytes " +
                         "FROM c GROUP BY c.storageTier";
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tierRows = (List<Map<String, Object>>) (List<?>) container()
                .queryItems(new SqlQuerySpec(tierSql), opts, Map.class)
                .collectList()
                .block();

        StatsDto stats = new StatsDto();
        long total = 0, totalBytes = 0;
        if (tierRows != null) {
            for (Map<String, Object> row : tierRows) {
                String t = (String) row.get("storageTier");
                long   c = ((Number) row.get("cnt")).longValue();
                long   b = row.get("bytes") != null ? ((Number) row.get("bytes")).longValue() : 0;
                total += c; totalBytes += b;
                switch (t != null ? t : "") {
                    case "Hot"     -> stats.setHot(c);
                    case "Cool"    -> stats.setCool(c);
                    case "Cold"    -> stats.setCold(c);
                    case "Archive" -> stats.setArchive(c);
                }
            }
        }
        stats.setTotal(total);
        stats.setTotalMb(totalBytes / (1024 * 1024));

        // 2. Department aggregation
        String deptSql = "SELECT c.department, COUNT(1) as cnt FROM c GROUP BY c.department";
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> deptRows = (List<Map<String, Object>>) (List<?>) container()
                .queryItems(new SqlQuerySpec(deptSql), opts, Map.class)
                .collectList()
                .block();

        List<Map<String, Object>> byDept = new ArrayList<>();
        if (deptRows != null) {
            for (Map<String, Object> row : deptRows) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("department", row.get("department"));
                entry.put("count", ((Number) row.get("cnt")).longValue());
                byDept.add(entry);
            }
        }
        stats.setByDepartment(byDept);

        return stats;
    }

    @Override
    public Page<Document> searchMetadata(String q, Pageable pageable) {
        String lq = q != null ? q.toLowerCase() : "";

        String where = " WHERE CONTAINS(LOWER(c.title), @q)" +
                       " OR CONTAINS(LOWER(c.author), @q)" +
                       " OR CONTAINS(LOWER(c.department), @q)" +
                       " OR CONTAINS(LOWER(c.description), @q)" +
                       " OR EXISTS(SELECT VALUE t FROM t IN c.tags WHERE CONTAINS(LOWER(t), @q))";

        String dataSql = "SELECT * FROM c" + where +
                         " ORDER BY c.uploadedAt DESC OFFSET " +
                         pageable.getOffset() + " LIMIT " + pageable.getPageSize();

        String countSql = "SELECT VALUE COUNT(1) FROM c" + where;

        SqlParameter param = new SqlParameter("@q", lq);
        CosmosQueryRequestOptions opts = new CosmosQueryRequestOptions();

        List<Document> items = container()
                .queryItems(new SqlQuerySpec(dataSql, List.of(param)), opts, Document.class)
                .collectList()
                .block();

        Long total = container()
                .queryItems(new SqlQuerySpec(countSql, List.of(param)), opts, Long.class)
                .blockFirst();

        return new PageImpl<>(items != null ? items : List.of(), pageable,
                              total != null ? total : 0);
    }

    @Override
    public List<Document> findHotDocsOlderThan(OffsetDateTime cutoff) {
        // Include docs where storageTier is 'Hot' OR null/undefined (treat missing as Hot)
        String sql = "SELECT * FROM c WHERE (c.storageTier = 'Hot' OR IS_NULL(c.storageTier) OR NOT IS_DEFINED(c.storageTier)) AND c.uploadedAt < @cutoff";
        SqlParameter param = new SqlParameter("@cutoff", cutoff.toString());
        List<Document> items = container()
                .queryItems(new SqlQuerySpec(sql, List.of(param)),
                        new CosmosQueryRequestOptions(), Document.class)
                .collectList()
                .block();
        return items != null ? items : List.of();
    }

    @Override
    public void updateLastAccessed(String id, OffsetDateTime at) {
        String sql = "SELECT * FROM c WHERE c.id = @id";
        Document doc = container()
                .queryItems(new SqlQuerySpec(sql, List.of(new SqlParameter("@id", id))),
                        new CosmosQueryRequestOptions(), Document.class)
                .blockFirst();
        if (doc != null) {
            doc.setLastAccessedAt(at);
            container().upsertItem(doc, new PartitionKey(doc.getDepartment()),
                    new CosmosItemRequestOptions()).block();
        }
    }

    @Override
    public void patchRestoreStatus(String id, String status, OffsetDateTime at) {
        String sql = "SELECT * FROM c WHERE c.id = @id";
        Document doc = container()
                .queryItems(new SqlQuerySpec(sql, List.of(new SqlParameter("@id", id))),
                        new CosmosQueryRequestOptions(), Document.class)
                .blockFirst();
        if (doc != null) {
            doc.setRestoreStatus(status);
            doc.setRestoreRequestedAt(at);
            container().upsertItem(doc, new PartitionKey(doc.getDepartment()),
                    new CosmosItemRequestOptions()).block();
        }
    }
}
