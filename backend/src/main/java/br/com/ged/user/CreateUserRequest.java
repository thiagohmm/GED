package br.com.ged.user;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateUserRequest(
        @NotBlank @Size(min = 3, max = 60) String username,
        @NotBlank @Size(min = 6, max = 100) String password,
        @NotNull Role role) {
}
