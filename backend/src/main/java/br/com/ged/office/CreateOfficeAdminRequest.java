package br.com.ged.office;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateOfficeAdminRequest(
        @NotBlank @Size(min = 3, max = 60) String username,
        @NotBlank @Size(min = 6, max = 100) String password) {
}
