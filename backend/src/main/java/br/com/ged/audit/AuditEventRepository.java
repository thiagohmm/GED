package br.com.ged.audit;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

public interface AuditEventRepository extends JpaRepository<AuditEvent, Long> {

    @Query("""
            select e from AuditEvent e
            where (:from is null or e.occurredAt >= :from)
              and (:to is null or e.occurredAt <= :to)
              and (:action is null or e.action = :action)
              and (:actor is null or lower(e.actorUsername) = lower(:actor))
              and (:resourceType is null or e.resourceType = :resourceType)
            """)
    Page<AuditEvent> findByFilters(@Param("from") Instant from,
                                   @Param("to") Instant to,
                                   @Param("action") String action,
                                   @Param("actor") String actor,
                                   @Param("resourceType") String resourceType,
                                   Pageable pageable);
}
