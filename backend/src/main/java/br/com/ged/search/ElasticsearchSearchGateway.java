package br.com.ged.search;

import br.com.ged.document.DocumentKind;
import br.com.ged.document.DocumentStatus;
import br.com.ged.document.DocumentVersion;
import br.com.ged.exception.ServiceUnavailableException;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.CountRequest;
import co.elastic.clients.elasticsearch.core.DeleteByQueryRequest;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

@Component
@Slf4j
public class ElasticsearchSearchGateway implements SearchGateway {

    static final String INDEX = "ged_versions";
    static final String HIGHLIGHT_OPEN = "\u0001";
    static final String HIGHLIGHT_CLOSE = "\u0002";

    private final boolean enabled;
    private final ElasticsearchClient client;

    public ElasticsearchSearchGateway(@Value("${ged.elasticsearch.uris}") String uris,
                                      @Value("${ged.elasticsearch.enabled}") boolean enabled) {
        this.enabled = enabled;
        this.client = enabled ? buildClient(uris) : null;
        if (!enabled) {
            log.info("Elasticsearch desativado por configuracao (ged.elasticsearch.enabled=false).");
        }
    }

    @Override
    public boolean isAvailable() {
        if (!enabled) {
            return false;
        }
        try {
            return client.ping().value();
        } catch (IOException | ElasticsearchException exception) {
            return false;
        }
    }

    @Override
    public void ensureIndex() {
        requireEnabled();
        try {
            boolean exists = client.indices().exists(index -> index.index(INDEX)).value();
            if (!exists) {
                createIndex();
            }
        } catch (IOException | ElasticsearchException exception) {
            throw new ServiceUnavailableException("Nao foi possivel verificar o indice de busca.");
        }
    }

    @Override
    public long countVersions() {
        requireEnabled();
        try {
            return client.count(CountRequest.of(count -> count.index(INDEX))).count();
        } catch (IOException | ElasticsearchException exception) {
            throw new ServiceUnavailableException("Indice de busca indisponivel.");
        }
    }

    @Override
    public void indexAll(List<DocumentVersion> versions, Function<DocumentVersion, String> folderPathOf) {
        requireEnabled();
        ensureIndex();
        try {
            BulkRequest.Builder bulk = new BulkRequest.Builder();
            int operations = 0;
            for (DocumentVersion version : versions) {
                if (version.getExtractedText() == null) {
                    continue;
                }
                bulk.operations(operation -> operation.index(index -> index
                        .index(INDEX)
                        .id(String.valueOf(version.getId()))
                        .document(toIndexedDocument(version, folderPathOf.apply(version)))));
                operations++;
            }
            if (operations > 0) {
                client.bulk(bulk.build());
            }
            log.info("Reindexacao concluida: {} versoes enviadas ao Elasticsearch.", operations);
        } catch (IOException | ElasticsearchException exception) {
            log.warn("Falha na reindexacao: {}", exception.getMessage());
        }
    }

    @Override
    public void indexVersion(DocumentVersion version, String folderPath) {
        if (!enabled || version.getExtractedText() == null) {
            return;
        }
        try {
            client.index(request -> request
                    .index(INDEX)
                    .id(String.valueOf(version.getId()))
                    .document(toIndexedDocument(version, folderPath)));
        } catch (IOException | RuntimeException exception) {
            log.warn("Falha ao indexar versao {}: {}", version.getId(), exception.getMessage());
        }
    }

    @Override
    public void deleteVersions(Long documentId) {
        if (!enabled) {
            return;
        }
        try {
            client.deleteByQuery(request -> request
                    .index(INDEX)
                    .query(query -> query.term(term -> term.field("documentId").value(documentId))));
        } catch (IOException | ElasticsearchException exception) {
            log.warn("Falha ao remover do indice o documento {}: {}", documentId, exception.getMessage());
        }
    }

    @Override
    public List<SearchResultItem> search(String query, DocumentStatus status) {
        if (!enabled) {
            throw new ServiceUnavailableException("Busca por texto esta indisponivel (Elasticsearch desativado).");
        }
        try {
            SearchRequest request = SearchRequest.of(search -> search
                    .index(INDEX)
                    .size(500)
                    .query(boolQuery(query, status))
                    .highlight(highlight -> highlight
                            .fields("content", field -> field
                                    .preTags(ElasticsearchSearchGateway.HIGHLIGHT_OPEN)
                                    .postTags(ElasticsearchSearchGateway.HIGHLIGHT_CLOSE)
                                    .fragmentSize(150)
                                    .numberOfFragments(1))));
            List<Hit<IndexedDocument>> hits = client.search(request, IndexedDocument.class).hits().hits();
            return newestVersionPerDocument(hits);
        } catch (IOException | ElasticsearchException exception) {
            throw new ServiceUnavailableException("Busca indisponivel no momento.");
        }
    }

    private Query boolQuery(String query, DocumentStatus status) {
        return Query.of(where -> where.bool(bool -> {
            bool.must(match -> match.multiMatch(multiMatch -> multiMatch
                    .query(query)
                    .fields("documentName^2", "content", "folderPath")));
            if (status != null) {
                bool.filter(allowed -> allowed.term(term -> term.field("status").value(status.name())));
            }
            return bool;
        }));
    }

    private List<SearchResultItem> newestVersionPerDocument(List<Hit<IndexedDocument>> rawHits) {
        List<Hit<IndexedDocument>> hits = rawHits.stream()
                .filter(hit -> hit.source() != null)
                .sorted((left, right) -> right.source().uploadedAt().compareTo(left.source().uploadedAt()))
                .toList();
        Map<Long, Hit<IndexedDocument>> newestPerDocument = new LinkedHashMap<>();
        for (Hit<IndexedDocument> hit : hits) {
            newestPerDocument.putIfAbsent(hit.source().documentId(), hit);
        }
        return newestPerDocument.values().stream()
                .map(this::toResult)
                .toList();
    }

    private SearchResultItem toResult(Hit<IndexedDocument> hit) {
        IndexedDocument document = hit.source();
        return new SearchResultItem(
                document.documentId(),
                document.versionNumber(),
                document.documentName(),
                DocumentKind.valueOf(document.kind()),
                DocumentStatus.valueOf(document.status()),
                document.folderPath(),
                snippetOf(hit),
                document.uploadedAt());
    }

    private String snippetOf(Hit<IndexedDocument> hit) {
        List<String> fragments = hit.highlight() == null ? List.of() : hit.highlight().get("content");
        if (fragments == null || fragments.isEmpty()) {
            return "";
        }
        String fragment = fragments.get(0);
        return fragment
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private IndexedDocument toIndexedDocument(DocumentVersion version, String folderPath) {
        return new IndexedDocument(
                version.getDocument().getId(),
                version.getDocument().getName(),
                folderPath,
                version.getDocument().getKind().name(),
                version.getDocument().getStatus() == null
                        ? DocumentStatus.EDITANDO.name() : version.getDocument().getStatus().name(),
                version.getVersionNumber(),
                version.getUploadedBy(),
                version.getUploadedAt(),
                version.getExtractedText());
    }

    private void createIndex() {
        try {
            client.indices().create(create -> create
                    .index(INDEX)
                    .mappings(mappings -> mappings
                            .properties("documentId", property -> property.long_(value -> value))
                            .properties("documentName", property -> property
                                    .text(text -> text.fields("raw", field -> field.keyword(keyword -> keyword))))
                            .properties("folderPath", property -> property.text(text -> text))
                            .properties("kind", property -> property.keyword(keyword -> keyword))
                            .properties("status", property -> property.keyword(keyword -> keyword))
                            .properties("versionNumber", property -> property.integer(integer -> integer))
                            .properties("uploadedBy", property -> property.keyword(keyword -> keyword))
                            .properties("uploadedAt", property -> property.date(date -> date))
                            .properties("content", property -> property.text(text -> text))));
            log.info("Indice {} criado.", INDEX);
        } catch (IOException | ElasticsearchException exception) {
            throw new ServiceUnavailableException("Nao foi possivel preparar o indice de busca.");
        }
    }

    private void requireEnabled() {
        if (!enabled || client == null) {
            throw new ServiceUnavailableException("Busca por texto esta indisponivel (Elasticsearch desativado).");
        }
    }

    private static ElasticsearchClient buildClient(String uris) {
        List<HttpHost> hosts = Arrays.stream(uris.split(","))
                .map(String::strip)
                .filter(uri -> !uri.isEmpty())
                .map(HttpHost::create)
                .toList();
        RestClient restClient = RestClient.builder(hosts.toArray(new HttpHost[0])).build();
        return new ElasticsearchClient(new RestClientTransport(restClient, new JacksonJsonpMapper(jsonMapperStatic())));
    }

    private static ObjectMapper jsonMapperStatic() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return mapper;
    }

    record IndexedDocument(Long documentId, String documentName, String folderPath, String kind, String status,
                           int versionNumber, String uploadedBy, Instant uploadedAt, String content) {
    }
}
