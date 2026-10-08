package br.com.ged.folder;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateFolderRequest(
        @NotBlank @Size(max = 255) String name,
        Long parentId,
        Long projectId,
        Long assignedAttorneyId) {

    public CreateFolderRequest(String name, Long parentId) {
        this(name, parentId, null, null);
    }
}
