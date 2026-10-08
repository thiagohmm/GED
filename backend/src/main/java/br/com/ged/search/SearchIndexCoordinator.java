package br.com.ged.search;

import br.com.ged.document.DocumentVersion;
import br.com.ged.document.DocumentVersionRepository;
import br.com.ged.folder.FolderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Slf4j
@Order(10)
public class SearchIndexCoordinator implements ApplicationRunner {

    private final SearchGateway searchGateway;
    private final DocumentVersionRepository documentVersionRepository;
    private final FolderService folderService;

    @Override
    @Transactional(readOnly = true)
    public void run(ApplicationArguments args) {
        if (!searchGateway.isAvailable()) {
            log.info("Elasticsearch indisponivel na subida; reindexacao nao executada.");
            return;
        }
        try {
            searchGateway.ensureIndex();
            if (searchGateway.countVersions() > 0) {
                log.info("Indice de busca ja possui dados; reindexacao ignorada.");
                return;
            }
            searchGateway.indexAll(documentVersionRepository.findAll(), this::folderPathOf);
        } catch (br.com.ged.exception.ServiceUnavailableException exception) {
            log.warn("Reindexacao adiada: {}", exception.getMessage());
        }
    }

    private String folderPathOf(DocumentVersion version) {
        return folderService.pathOf(version.getDocument().getFolder().getId());
    }
}
