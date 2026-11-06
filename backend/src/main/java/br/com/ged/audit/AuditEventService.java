package br.com.ged.audit;

import br.com.ged.config.AuthenticatedUser;
import br.com.ged.tenant.TenantScope;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;

/**
 * Trilha de auditoria imutavel. Cada evento grava quem fez, o que fez e em qual recurso.
 * Grava em transicao propria para que falhas do negocio nao percam o registro e o registro
 * nao embarace a transacao principal.
 */
@Service
public class AuditEventService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AuditEventRepository repository;
    private final TenantScope tenantScope;

    public AuditEventService(AuditEventRepository repository, TenantScope tenantScope) {
        this.repository = repository;
        this.tenantScope = tenantScope;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String action, String resourceType, String resourceId, Object detail) {
        AuthenticatedUser principal = tenantScope.currentPrincipal();
        AuditEvent event = new AuditEvent(
                Instant.now(),
                principal == null ? null : principal.username(),
                principal == null ? null : principal.role().name(),
                action,
                resourceType,
                resourceId,
                toJson(detail),
                null);
        repository.save(event);
    }

    public Page<AuditEvent> list(Instant from, Instant to, String action, String actor,
                                 String resourceType, Pageable pageable) {
        return repository.findByFilters(from, to, action, actor, resourceType, pageable);
    }

    private String toJson(Object detail) {
        if (detail == null) {
            return null;
        }
        if (detail instanceof String text) {
            return text;
        }
        try {
            return MAPPER.writeValueAsString(detail);
        } catch (JsonProcessingException exception) {
            return String.valueOf(detail);
        }
    }

    public static Map<String, Object> details(Object... pairs) {
        java.util.LinkedHashMap<String, Object> map = new java.util.LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            map.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return map;
    }
}
