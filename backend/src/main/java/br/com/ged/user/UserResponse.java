package br.com.ged.user;

import java.time.Instant;

public record UserResponse(Long id, String username, Role role, boolean enabled, Instant createdAt) {

    public static UserResponse from(User user) {
        return new UserResponse(user.getId(), user.getUsername(), user.getRole(), user.isEnabled(), user.getCreatedAt());
    }
}
