package br.com.ged.review;

import java.time.Instant;

public record ReviewCommentResponse(Long id, String author, String body,
                                     Integer diffLineIndex, String diffLineContent, Instant createdAt) {

    public static ReviewCommentResponse from(ReviewComment comment) {
        return new ReviewCommentResponse(comment.getId(), comment.getAuthor(), comment.getBody(),
                comment.getDiffLineIndex(), comment.getDiffLineContent(), comment.getCreatedAt());
    }
}
