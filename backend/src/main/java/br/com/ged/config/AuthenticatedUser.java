package br.com.ged.config;

import br.com.ged.user.Role;

import java.util.Optional;

/**
 * Principal autenticado. O papel e o escritorio sao recarregados do banco em todo request
 * pelo {@link JwtAuthenticationFilter} (o token JWT carrega apenas o username), de modo que
 * desativacao, troca de papel ou de escritorio valem imediatamente.
 */
public record AuthenticatedUser(Long userId, String username, Role role, Long officeId) {

    public boolean isSuperAdmin() {
        return role == Role.SUPER_ADMIN;
    }

    /**
     * Escopo de escritorio do usuario. Vazio = sem restricao (SUPER_ADMIN, ou chamada sem
     * contexto autenticado, como em testes unitarios e no callback do editor).
     */
    public Optional<Long> officeScope() {
        if (isSuperAdmin()) {
            return Optional.empty();
        }
        return Optional.ofNullable(officeId);
    }
}
