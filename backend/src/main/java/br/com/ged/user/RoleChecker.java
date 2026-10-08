package br.com.ged.user;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class RoleChecker {

    public boolean currentHasAny(Collection<Role> roles) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return false;
        }
        Set<String> wanted = roles.stream()
                .map(role -> "ROLE_" + role.name())
                .collect(Collectors.toSet());
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(wanted::contains);
    }

    public void requireAny(Collection<Role> roles) {
        if (!currentHasAny(roles)) {
            throw new AccessDeniedException("Seu perfil nao tem permissao para esta operacao.");
        }
    }
}
