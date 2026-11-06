package br.com.ged.audit;

import java.time.Instant;

public record AuditEventResponse(Long id, Instant occurredAt, String actorUsername, String actorRole,
                                 String action, String resourceType, String resourceId, String detail) {

    public static AuditEventResponse from(AuditEvent event) {
        return new AuditEventResponse(event.getId(), event.getOccurredAt(), event.getActorUsername(),
                event.getActorRole(), event.getAction(), event.getResourceType(), event.getResourceId(),
                event.getDetail());
    }
}
