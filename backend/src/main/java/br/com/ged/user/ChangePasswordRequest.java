package br.com.ged.user;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChangePasswordRequest(@NotBlank String currentPassword,
                                    @NotBlank @Size(min = 6, max = 100) String newPassword) {
}
