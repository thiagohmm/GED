package br.com.ged.review;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record DecideReviewRequest(
        @NotNull ReviewVerdict verdict,
        @Size(max = 2000) String summary) {
}
