package br.com.ged.config;

import br.com.ged.user.User;
import br.com.ged.user.UserRepository;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * Autenticacao stateless por JWT. O token carrega apenas o username; papel, escritorio e
 * status do usuario sao recarregados do banco em todo request, de modo que mudancas de
 * papel/escritorio/desativacao valem imediatamente.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String AUTHORIZATION_HEADER = "Authorization";

    private final JwtService jwtService;
    private final UserRepository userRepository;

    public JwtAuthenticationFilter(JwtService jwtService, UserRepository userRepository) {
        this.jwtService = jwtService;
        this.userRepository = userRepository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        extractBearerToken(request)
                .flatMap(jwtService::usernameOf)
                .flatMap(username -> userRepository.findByUsernameIgnoreCase(username))
                .filter(User::isEnabled)
                .map(user -> new AuthenticatedUser(user.getId(), user.getUsername(), user.getRole(), user.getOfficeId()))
                .ifPresent(JwtAuthenticationFilter::authenticateWith);

        filterChain.doFilter(request, response);
    }

    private Optional<String> extractBearerToken(HttpServletRequest request) {
        String header = request.getHeader(AUTHORIZATION_HEADER);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return Optional.empty();
        }
        return Optional.of(header.substring(BEARER_PREFIX.length()));
    }

    private static void authenticateWith(AuthenticatedUser principal) {
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                principal,
                null,
                List.of(new SimpleGrantedAuthority("ROLE_" + principal.role().name())));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
