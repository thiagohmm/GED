package br.com.ged.config;

import br.com.ged.user.Role;
import org.springframework.security.access.AccessDeniedException;

import java.util.Optional;

/**
 * Principal autenticado. O papel e o escritorio sao recarregados do banco em todo request
 * pelo {@link JwtAuthenticationFilter}, de modo que desativacao, troca de papel, escritorio
 * ou senha (via tokenVersion) valem imediatamente.
 */
public record AuthenticatedUser(Long userId, String username, Role role, Long officeId) {

    public boolean isSuperAdmin() {
        return role == Role.SUPER_ADMIN;
    }

    /**
     * Escopo de escritorio do usuario. Vazio apenas para SUPER_ADMIN; perfis operacionais
     * sem escritorio vinculado sao rejeitados.
     */
    public Optional<Long> officeScope() {
        if (isSuperAdmin()) {
            return Optional.empty();
        }
        if (officeId == null) {
            throw new AccessDeniedException("Usuario operacional sem escritorio vinculado.");
        }
        return Optional.of(officeId);
    }
}
