package br.com.ged.user;

import java.time.Instant;

public record UserResponse(Long id, String username, Role role, boolean enabled, Long officeId,
                           String officeName, Instant createdAt) {

    public static UserResponse from(User user) {
        return new UserResponse(user.getId(), user.getUsername(), user.getRole(), user.isEnabled(),
                user.getOfficeId(), null, user.getCreatedAt());
    }

    public UserResponse withOfficeName(String name) {
        return new UserResponse(id, username, role, enabled, officeId, name, createdAt);
    }
}
