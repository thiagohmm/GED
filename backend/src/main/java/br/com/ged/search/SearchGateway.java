package br.com.ged.search;

import br.com.ged.document.DocumentStatus;
import br.com.ged.document.DocumentVersion;

import java.util.List;
import java.util.function.Function;

public interface SearchGateway {

    boolean isAvailable();

    void ensureIndex();

    long countVersions();

    void indexAll(List<DocumentVersion> versions, Function<DocumentVersion, String> folderPathOf);

    void indexVersion(DocumentVersion version, String folderPath);

    void deleteVersions(Long documentId);

    List<SearchResultItem> search(String query, DocumentStatus status);
}
