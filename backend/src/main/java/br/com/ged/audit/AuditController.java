package br.com.ged.audit;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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
            @RequestParam(required = false) Integer page,
            @RequestParam(defaultValue = "0") long offset,
            @RequestParam(defaultValue = "50") int limit) {
        int size = Math.min(Math.max(limit, 1), 200);
        long resolvedOffset = page != null ? (long) Math.max(page, 0) * size : Math.max(offset, 0L);
        Pageable pageable = new OffsetBasedPageRequest(
                resolvedOffset, size, Sort.by(Sort.Direction.DESC, "occurredAt"));
        Page<AuditEvent> result = auditEventService.list(from, to, action, actor, resourceType, pageable);
        return ResponseEntity.ok(result.map(AuditEventResponse::from));
    }

    static final class OffsetBasedPageRequest implements Pageable {

        private final long offset;
        private final int limit;
        private final Sort sort;

        OffsetBasedPageRequest(long offset, int limit, Sort sort) {
            this.offset = Math.max(offset, 0L);
            this.limit = Math.max(limit, 1);
            this.sort = sort == null ? Sort.unsorted() : sort;
        }

        @Override
        public int getPageNumber() {
            return (int) (offset / limit);
        }

        @Override
        public int getPageSize() {
            return limit;
        }

        @Override
        public long getOffset() {
            return offset;
        }

        @Override
        public Sort getSort() {
            return sort;
        }

        @Override
        public Pageable next() {
            return new OffsetBasedPageRequest(offset + limit, limit, sort);
        }

        @Override
        public Pageable previousOrFirst() {
            return hasPrevious() ? new OffsetBasedPageRequest(Math.max(offset - limit, 0L), limit, sort) : first();
        }

        @Override
        public Pageable first() {
            return new OffsetBasedPageRequest(0L, limit, sort);
        }

        @Override
        public Pageable withPage(int pageNumber) {
            return new OffsetBasedPageRequest((long) Math.max(pageNumber, 0) * limit, limit, sort);
        }

        @Override
        public boolean hasPrevious() {
            return offset > 0;
        }
    }
}
