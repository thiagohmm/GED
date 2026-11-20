package br.com.ged.search;

import br.com.ged.document.DocumentKind;
import br.com.ged.document.DocumentStatus;
import br.com.ged.document.DocumentVersion;
import br.com.ged.exception.ServiceUnavailableException;
import br.com.ged.folder.Folder;
import br.com.ged.project.Project;
import br.com.ged.tenant.TenantScope;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.CountRequest;
import co.elastic.clients.elasticsearch.core.DeleteByQueryRequest;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.indices.GetIndicesSettingsResponse;
import co.elastic.clients.elasticsearch.indices.IndexState;
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
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.Optional;

@Component
@Slf4j
public class ElasticsearchSearchGateway implements SearchGateway {

    static final String INDEX = "ged_versions";
    static final String HIGHLIGHT_OPEN = "\u0001";
    static final String HIGHLIGHT_CLOSE = "\u0002";

    /**
     * Analyzer customizado que indexa todos os prefixes de cada token (edge-ngram 2..15) permitindo
     * a busca por qualquer trecho de texto (partial/substring), por exemplo "docu" encontra "documento".
     * O lado da consulta usa o analyzer "standard" (build-in) para tokenizar o termo digitado normalmente.
     */
    static final String SEARCH_ANALYZER = "ged_search_analyzer";

    private final boolean enabled;
    private final ElasticsearchClient client;
    private final TenantScope tenantScope;
    private volatile boolean needsReindex = false;

    public ElasticsearchSearchGateway(@Value("${ged.elasticsearch.uris}") String uris,
                                      @Value("${ged.elasticsearch.enabled}") boolean enabled,
                                      TenantScope tenantScope) {
        this.enabled = enabled;
        this.client = enabled ? buildClient(uris) : null;
        this.tenantScope = tenantScope;
        if (!enabled) {
            log.info("Elasticsearch desativado por configuracao (ged.elasticsearch.enabled=false).");
        }
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public boolean isAvailable() {
        if (!enabled || client == null) {
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
        needsReindex = false;
        try {
            boolean exists = client.indices().exists(index -> index.index(INDEX)).value();
            if (!exists) {
                createIndex();
            } else {
                needsReindex = needsMappingReindex();
            }
        } catch (IOException | ElasticsearchException exception) {
            throw new ServiceUnavailableException("Nao foi possivel verificar o indice de busca.");
        }
    }

    private boolean needsMappingReindex() {
        try {
            GetIndicesSettingsResponse response = client.indices().getSettings(g -> g.index(INDEX));
            IndexState state = response.get(INDEX);
            if (state == null || state.settings() == null || state.settings().analysis() == null) {
                return true;
            }
            java.util.Map<String, co.elastic.clients.elasticsearch._types.analysis.Analyzer> analyzers =
                    state.settings().analysis().analyzer();
            return analyzers == null || !analyzers.containsKey(SEARCH_ANALYZER);
        } catch (IOException | ElasticsearchException exception) {
            log.warn("Nao foi possivel verificar o mapeamento do indice: {}", exception.getMessage());
            return true;
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
                        .document(toIndexedDocument(version, folderPathOf.apply(version), officeIdOf(version)))));
                operations++;
            }
            if (operations > 0) {
                var bulkResponse = client.bulk(bulk.refresh(co.elastic.clients.elasticsearch._types.Refresh.True).build());
                if (bulkResponse.errors()) {
                    log.warn("Reindexacao bulk reportou erros parciais em um ou mais documentos.");
                }
            }
            log.info("Reindexacao concluida: {} versoes enviadas ao Elasticsearch.", operations);
        } catch (IOException | ElasticsearchException exception) {
            log.warn("Falha na reindexacao: {}", exception.getMessage());
        }
    }

    @Override
    public void indexVersion(DocumentVersion version, String folderPath) {
        if (!enabled || version.getExtractedText() == null || version.getId() == null) {
            return;
        }
        String docId = String.valueOf(version.getId());
        IndexedDocument payload = toIndexedDocument(version, folderPath, officeIdOf(version));
        runAfterCommit(() -> {
            try {
                client.index(request -> request
                        .index(INDEX)
                        .id(docId)
                        .document(payload));
            } catch (IOException | RuntimeException exception) {
                log.warn("Falha ao indexar versao {}: {}", docId, exception.getMessage());
            }
        });
    }

    @Override
    public void deleteVersions(Long documentId) {
        if (!enabled) {
            return;
        }
        runAfterCommit(() -> {
            try {
                client.deleteByQuery(request -> request
                        .index(INDEX)
                        .query(query -> query.term(term -> term.field("documentId").value(documentId))));
            } catch (IOException | ElasticsearchException exception) {
                log.warn("Falha ao remover do indice o documento {}: {}", documentId, exception.getMessage());
            }
        });
    }

    private void runAfterCommit(Runnable action) {
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            action.run();
                        }
                    });
        } else {
            action.run();
        }
    }

    @Override
    public void clearIndex() {
        if (!enabled) {
            return;
        }
        try {
            client.deleteByQuery(request -> request
                    .index(INDEX)
                    .query(query -> query.matchAll(m -> m)));
        } catch (IOException | ElasticsearchException exception) {
            log.warn("Falha ao limpar o indice de busca: {}", exception.getMessage());
        }
    }

    @Override
    public boolean needsReindex() {
        return needsReindex;
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
                    .query(boolQuery(query, status, tenantScope.currentOffice()))
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

    private Query boolQuery(String query, DocumentStatus status, Optional<Long> officeScope) {
        return Query.of(where -> where.bool(bool -> {
            bool.must(match -> match.multiMatch(multiMatch -> multiMatch
                    .query(query)
                    .fields("documentName^2", "content", "folderPath")));
            if (status != null) {
                bool.filter(allowed -> allowed.term(term -> term.field("status").value(status.name())));
            }
            if (officeScope != null && officeScope.isPresent()) {
                bool.filter(officeFilter -> officeFilter.term(term -> term.field("officeId").value(officeScope.get())));
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

    private IndexedDocument toIndexedDocument(DocumentVersion version, String folderPath, Long officeId) {
        return new IndexedDocument(
                version.getDocument().getId(),
                version.getDocument().getName(),
                folderPath,
                officeId,
                version.getDocument().getKind().name(),
                version.getDocument().getStatus() == null
                        ? DocumentStatus.EDITANDO.name() : version.getDocument().getStatus().name(),
                version.getVersionNumber(),
                version.getUploadedBy(),
                version.getUploadedAt() == null ? java.time.Instant.now() : version.getUploadedAt(),
                version.getExtractedText());
    }

    /**
     * Escritorio do documento: via pasta raiz -> projeto -> officeId. Nulo quando nao ha raiz/projeto.
     */
    private Long officeIdOf(DocumentVersion version) {
        Folder folder = version.getDocument() == null ? null : version.getDocument().getFolder();
        if (folder == null) {
            return null;
        }
        Folder root = folder;
        while (root.getParent() != null) {
            root = root.getParent();
        }
        Project project = root.getProject();
        return project == null ? null : project.getOfficeId();
    }

    private void createIndex() {
        try {
            client.indices().create(create -> create
                    .index(INDEX)
                    .settings(settings -> settings
                            .analysis(analysis -> analysis
                                    .analyzer(SEARCH_ANALYZER, analyzer -> analyzer
                                            .custom(custom -> custom
                                                    .tokenizer("standard")
                                                    .filter(java.util.Arrays.asList("lowercase", SEARCH_ANALYZER))))
                                    .filter(SEARCH_ANALYZER, filter -> filter
                                            .definition(definition -> definition
                                                    .edgeNgram(eng -> eng.minGram(2).maxGram(15))))))
                    .mappings(mappings -> mappings
                            .properties("documentId", property -> property.long_(value -> value))
                            .properties("documentName", property -> property.text(text -> text
                                    .analyzer(SEARCH_ANALYZER)
                                    .searchAnalyzer("standard")))
                            .properties("folderPath", property -> property.text(text -> text
                                    .analyzer(SEARCH_ANALYZER)
                                    .searchAnalyzer("standard")))
                            .properties("kind", property -> property.keyword(keyword -> keyword))
                            .properties("officeId", property -> property.long_(value -> value))
                            .properties("status", property -> property.keyword(keyword -> keyword))
                            .properties("versionNumber", property -> property.integer(integer -> integer))
                            .properties("uploadedBy", property -> property.keyword(keyword -> keyword))
                            .properties("uploadedAt", property -> property.date(date -> date))
                            .properties("content", property -> property.text(text -> text
                                    .analyzer(SEARCH_ANALYZER)
                                    .searchAnalyzer("standard")))));
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

    @JsonIgnoreProperties(ignoreUnknown = true)
    record IndexedDocument(Long documentId, String documentName, String folderPath, Long officeId, String kind, String status,
                           int versionNumber, String uploadedBy, Instant uploadedAt, String content) {
    }
}
