package br.com.ged.search;

import br.com.ged.document.DocumentStatus;
import br.com.ged.document.DocumentVersion;

import java.util.List;
import java.util.function.Function;

public interface SearchGateway {

    /** true se a busca foi habilitada por configuracao (independente de disponibilidade em tempo real). */
    boolean isEnabled();

    boolean isAvailable();

    void ensureIndex();

    long countVersions();

    void indexAll(List<DocumentVersion> versions, Function<DocumentVersion, String> folderPathOf);

    void indexVersion(DocumentVersion version, String folderPath);

    void deleteVersions(Long documentId);

    /** Remove todos os documentos do indice (reindexacao completa). */
    void clearIndex();

    List<SearchResultItem> search(String query, DocumentStatus status);
}
