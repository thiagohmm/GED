package br.com.ged.auth;

import br.com.ged.user.Role;

public record LoginResponse(String token, String username, Role role) {
}
