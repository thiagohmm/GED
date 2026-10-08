package br.com.ged.user;

import jakarta.validation.constraints.Size;

public record UpdateUserRequest(
        @Size(min = 6, max = 100) String password,
        Role role,
        Boolean enabled) {
}
