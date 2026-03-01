package com.docvault.repository;

import com.azure.cosmos.CosmosAsyncClient;
import com.azure.cosmos.CosmosAsyncContainer;
import com.azure.cosmos.models.*;
import com.docvault.dto.StatsDto;
import com.docvault.model.Document;
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
            CosmosAsyncClient cosmosClient,
            @Value("${azure.cosmos.database:DocVaultDB}") String database,
            @Value("${azure.cosmos.container:documents}") String containerName) {
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
        String sql = "SELECT c.storageTier, COUNT(1) as cnt, SUM(c.fileSizeBytes) as bytes " +
                     "FROM c GROUP BY c.storageTier";

        List<Map> rows = container()
                .queryItems(new SqlQuerySpec(sql), new CosmosQueryRequestOptions(), Map.class)
                .collectList()
                .block();

        StatsDto stats = new StatsDto();
        long total = 0, totalBytes = 0;
        if (rows != null) {
            for (Map row : rows) {
                String t = (String) row.get("storageTier");
                long   c = ((Number) row.get("cnt")).longValue();
                long   b = row.get("bytes") != null ? ((Number)row.get("bytes")).longValue() : 0;
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
        return stats;
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
