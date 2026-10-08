package br.com.ged.review;

import br.com.ged.document.DocumentStatus;

import java.time.Instant;
import java.util.List;

public record ReviewResponse(Long id, Long documentId, String documentName, DocumentStatus documentStatus,
                              int round, int fromVersion, int toVersion, ReviewVerdict verdict,
                              String openedBy, Instant openedAt, String decidedBy, Instant decidedAt,
                              String summary, List<ReviewCommentResponse> comments) {

    public static ReviewResponse from(Review review, List<ReviewCommentResponse> comments) {
        return new ReviewResponse(
                review.getId(),
                review.getDocument().getId(),
                review.getDocument().getName(),
                review.getDocument().getStatus(),
                review.getRound(),
                review.getFromVersion(),
                review.getToVersion(),
                review.getVerdict(),
                review.getCreatedBy(),
                review.getCreatedAt(),
                review.getDecidedBy(),
                review.getDecidedAt(),
                review.getSummary(),
                comments);
    }
}
