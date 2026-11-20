package br.com.ged.search;

import br.com.ged.document.DocumentVersionRepository;
import br.com.ged.exception.ConflictException;
import br.com.ged.exception.ServiceUnavailableException;
import br.com.ged.folder.FolderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.locks.ReentrantLock;

/**
 * Reconstrucao do indice de busca a partir do Postgres (fonte da verdade).
 * Reaproveitado no startup, no restore de backup e no admin POST /api/admin/reindex.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReindexService {

    private final ReentrantLock reindexLock = new ReentrantLock();
    private final SearchGateway searchGateway;
    private final DocumentVersionRepository documentVersionRepository;
    private final FolderService folderService;

    @Transactional(readOnly = true)
    public boolean reindexIfEmpty() {
        if (!reindexLock.tryLock()) {
            log.info("Reindexacao ja em andamento; chamada de startup ignorada.");
            return false;
        }
        try {
            if (!searchGateway.isEnabled()) {
                return false;
            }
            if (!searchGateway.isAvailable()) {
                log.info("Elasticsearch indisponivel na subida; reindexacao adiada.");
                return false;
            }
            searchGateway.ensureIndex();
            if (searchGateway.needsReindex()) {
                log.info("Mapeamento do indice alterado; reconstruindo o indice de busca.");
                searchGateway.clearIndex();
                searchGateway.indexAll(documentVersionRepository.findAll(), this::folderPathOf);
                return true;
            }
            if (searchGateway.countVersions() > 0) {
                log.info("Indice de busca ja possui dados; reindexacao ignorada.");
                return false;
            }
            searchGateway.indexAll(documentVersionRepository.findAll(), this::folderPathOf);
            return true;
        } finally {
            reindexLock.unlock();
        }
    }

    @Transactional(readOnly = true)
    public boolean reindexAll() {
        if (!reindexLock.tryLock()) {
            throw new ConflictException("Reindexacao ja esta em andamento.");
        }
        try {
            if (!searchGateway.isEnabled()) {
                return false;
            }
            if (!searchGateway.isAvailable()) {
                throw new ServiceUnavailableException("Elasticsearch indisponivel; reindexacao nao executada.");
            }
            searchGateway.clearIndex();
            searchGateway.indexAll(documentVersionRepository.findAll(), this::folderPathOf);
            return true;
        } finally {
            reindexLock.unlock();
        }
    }

    private String folderPathOf(br.com.ged.document.DocumentVersion version) {
        return folderService.pathOf(version.getDocument().getFolder().getId());
    }
}
