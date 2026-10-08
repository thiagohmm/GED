package br.com.ged.folder;

import jakarta.validation.constraints.NotNull;

public record AssignRootFolderRequest(@NotNull Long projectId, @NotNull Long assignedAttorneyId) {
}
