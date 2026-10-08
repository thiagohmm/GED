package br.com.ged.review;

import br.com.ged.document.DocumentStatus;

import java.time.Instant;

public record ReviewQueueItem(Long reviewId, Long documentId, String documentName, DocumentStatus documentStatus,
                               String folderPath, int round, int fromVersion, int toVersion,
                               ReviewVerdict verdict, String openedBy, Instant openedAt,
                               String decidedBy, Instant decidedAt) {

    public static ReviewQueueItem from(Review review, String folderPath) {
        return new ReviewQueueItem(
                review.getId(),
                review.getDocument().getId(),
                review.getDocument().getName(),
                review.getDocument().getStatus(),
                folderPath,
                review.getRound(),
                review.getFromVersion(),
                review.getToVersion(),
                review.getVerdict(),
                review.getCreatedBy(),
                review.getCreatedAt(),
                review.getDecidedBy(),
                review.getDecidedAt());
    }
}
