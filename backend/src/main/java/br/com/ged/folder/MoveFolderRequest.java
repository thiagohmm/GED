package br.com.ged.folder;

import jakarta.validation.constraints.NotNull;

public record MoveFolderRequest(@NotNull Long targetFolderId) {
}
