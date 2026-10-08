package br.com.ged.review;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AddCommentRequest(
        @NotBlank @Size(max = 4000) String body,
        Integer diffLineIndex,
        @Size(max = 500) String diffLineContent) {
}
