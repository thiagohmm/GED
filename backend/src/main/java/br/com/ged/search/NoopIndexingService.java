package br.com.ged.search;

import br.com.ged.document.DocumentStatus;
import br.com.ged.document.DocumentVersion;
import br.com.ged.exception.ServiceUnavailableException;

import java.util.List;
import java.util.function.Function;

public class NoopIndexingService implements SearchGateway {

    @Override
    public boolean isAvailable() {
        return false;
    }

    @Override
    public void ensureIndex() {
    }

    @Override
    public long countVersions() {
        return 0;
    }

    @Override
    public void indexAll(List<DocumentVersion> versions, Function<DocumentVersion, String> folderPathOf) {
    }

    @Override
    public void indexVersion(DocumentVersion version, String folderPath) {
    }

    @Override
    public void deleteVersions(Long documentId) {
    }

    @Override
    public List<SearchResultItem> search(String query, DocumentStatus status) {
        throw new ServiceUnavailableException("Busca indisponivel.");
    }
}
