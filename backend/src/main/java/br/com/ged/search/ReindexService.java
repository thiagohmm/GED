package br.com.ged.search;

import br.com.ged.document.DocumentVersionRepository;
import br.com.ged.folder.FolderService;
import br.com.ged.exception.ServiceUnavailableException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reconstrucao do indice de busca a partir do Postgres (fonte da verdade).
 * Reaproveitado no startup, no restore de backup e no admin POST /api/admin/reindex.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReindexService {

    private final SearchGateway searchGateway;
    private final DocumentVersionRepository documentVersionRepository;
    private final FolderService folderService;

    /**
     * Startup: reconstrui apenas se o indice estiver vazio (nao descarta dados recentes).
     *
     * @return true se o indice foi populado nesta chamada.
     */
    @Transactional(readOnly = true)
    public boolean reindexIfEmpty() {
        if (!searchGateway.isEnabled()) {
            return false;
        }
        if (!searchGateway.isAvailable()) {
            log.info("Elasticsearch indisponivel na subida; reindexacao adiada.");
            return false;
        }
        searchGateway.ensureIndex();
        if (searchGateway.countVersions() > 0) {
            log.info("Indice de busca ja possui dados; reindexacao ignorada.");
            return false;
        }
        searchGateway.indexAll(documentVersionRepository.findAll(), this::folderPathOf);
        return true;
    }

    /**
     * Restore/admin: limpa o indice e reconstrui tudo do banco.
     *
     * @return true se o indice foi (re)construido; false se a busca estiver desativada.
     * @throws ServiceUnavailableException se a busca estiver ativa mas o Elasticsearch estiver fora.
     */
    @Transactional(readOnly = true)
    public boolean reindexAll() {
        if (!searchGateway.isEnabled()) {
            return false;
        }
        if (!searchGateway.isAvailable()) {
            throw new ServiceUnavailableException("Elasticsearch indisponivel; reindexacao nao executada.");
        }
        searchGateway.clearIndex();
        searchGateway.indexAll(documentVersionRepository.findAll(), this::folderPathOf);
        return true;
    }

    private String folderPathOf(br.com.ged.document.DocumentVersion version) {
        return folderService.pathOf(version.getDocument().getFolder().getId());
    }
}
