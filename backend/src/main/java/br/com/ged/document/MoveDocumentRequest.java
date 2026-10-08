package br.com.ged.document;

import jakarta.validation.constraints.NotNull;

public record MoveDocumentRequest(@NotNull Long targetFolderId) {
}
