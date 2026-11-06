package br.com.ged.search;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Forca a reconstrucao do indice de busca a partir do Postgres (SUPER_ADMIN).
 */
@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class ReindexController {

    private final ReindexService reindexService;

    public ReindexController(ReindexService reindexService) {
        this.reindexService = reindexService;
    }

    @PostMapping("/reindex")
    public Map<String, Object> reindex() {
        boolean reindexed = reindexService.reindexAll();
        return Map.of("reindexed", reindexed);
    }
}
