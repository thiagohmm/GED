package br.com.ged.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "audit_events")
@Getter
@Setter
@NoArgsConstructor
public class AuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "actor_username", length = 60)
    private String actorUsername;

    @Column(name = "actor_role", length = 60)
    private String actorRole;

    @Column(nullable = false, length = 60)
    private String action;

    @Column(name = "resource_type", length = 40)
    private String resourceType;

    @Column(name = "resource_id", length = 60)
    private String resourceId;

    @Column(columnDefinition = "text")
    private String detail;

    @Column(length = 64)
    private String ip;

    public AuditEvent(Instant occurredAt, String actorUsername, String actorRole, String action,
                      String resourceType, String resourceId, String detail, String ip) {
        this.occurredAt = occurredAt;
        this.actorUsername = actorUsername;
        this.actorRole = actorRole;
        this.action = action;
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.detail = detail;
        this.ip = ip;
    }
}
