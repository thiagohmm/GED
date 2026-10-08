package br.com.ged.review;

import br.com.ged.document.DocumentStatus;

import java.time.Instant;

public record DocumentReviewsItem(Long reviewId, DocumentStatus documentStatus, int round, int fromVersion,
                                    int toVersion, ReviewVerdict verdict, String openedBy, java.time.Instant openedAt,
                                    String decidedBy, Instant decidedAt, long commentCount) {
}
