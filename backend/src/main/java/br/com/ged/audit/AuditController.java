package br.com.ged.audit;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * Consulta da trilha de auditoria (imutavel): exclusiva do SUPER_ADMIN.
 */
@RestController
@RequestMapping("/api/admin/audit")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class AuditController {

    private final AuditEventService auditEventService;

    public AuditController(AuditEventService auditEventService) {
        this.auditEventService = auditEventService;
    }

    @GetMapping
    public ResponseEntity<Page<AuditEventResponse>> list(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String actor,
            @RequestParam(required = false) String resourceType,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "50") int limit) {
        int size = Math.min(Math.max(limit, 1), 200);
        Page<AuditEvent> page = auditEventService.list(from, to, action, actor, resourceType,
                PageRequest.of(Math.max(offset, 0) / size, size, Sort.by(Sort.Direction.DESC, "occurredAt")));
        return ResponseEntity.ok(page.map(AuditEventResponse::from));
    }
}
