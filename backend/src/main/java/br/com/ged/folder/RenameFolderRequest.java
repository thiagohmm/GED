package br.com.ged.folder;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RenameFolderRequest(
        @NotBlank @Size(max = 255) String name) {
}
