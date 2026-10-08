package br.com.ged.document;

import java.time.Instant;

public record DocumentStatusChangeResponse(Long id, DocumentStatus fromStatus, DocumentStatus toStatus,
                                           String note, String changedBy, Instant changedAt) {

    public static DocumentStatusChangeResponse from(DocumentStatusChange change) {
        return new DocumentStatusChangeResponse(change.getId(), change.getFromStatus(), change.getToStatus(),
                change.getNote(), change.getChangedBy(), change.getChangedAt());
    }
}
