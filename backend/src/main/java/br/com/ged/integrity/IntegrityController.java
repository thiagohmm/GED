package br.com.ged.integrity;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Auditoria de integridade da storage: exclusiva do SUPER_ADMIN.
 */
@RestController
@RequestMapping("/api/admin/integrity")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class IntegrityController {

    private final IntegrityService integrityService;

    public IntegrityController(IntegrityService integrityService) {
        this.integrityService = integrityService;
    }

    @GetMapping
    public ResponseEntity<IntegrityReport> check() {
        return ResponseEntity.ok(integrityService.check());
    }
}
