package br.com.ged.config;

import br.com.ged.office.Office;
import br.com.ged.office.OfficeRepository;
import br.com.ged.user.Role;
import br.com.ged.user.User;
import br.com.ged.user.UserRepository;
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
 * Autenticacao stateless por JWT. Valida assinatura e typ=ACCESS, confere tokenVersion
 * (invalidando tokens apos troca de senha), status ativo do usuario e status ativo do escritorio.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String AUTHORIZATION_HEADER = "Authorization";

    private final JwtService jwtService;
    private final UserRepository userRepository;
    private final OfficeRepository officeRepository;

    public JwtAuthenticationFilter(JwtService jwtService,
                                   UserRepository userRepository,
                                   OfficeRepository officeRepository) {
        this.jwtService = jwtService;
        this.userRepository = userRepository;
        this.officeRepository = officeRepository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        extractBearerToken(request)
                .flatMap(jwtService::verifyAccessToken)
                .flatMap(claims -> userRepository.findByUsernameIgnoreCase(claims.username())
                        .filter(User::isEnabled)
                        .filter(user -> user.getTokenVersion() == claims.tokenVersion())
                        .filter(this::hasValidActiveOffice))
                .map(user -> new AuthenticatedUser(user.getId(), user.getUsername(), user.getRole(), user.getOfficeId()))
                .ifPresent(JwtAuthenticationFilter::authenticateWith);

        filterChain.doFilter(request, response);
    }

    private boolean hasValidActiveOffice(User user) {
        if (user.getRole() == Role.SUPER_ADMIN) {
            return true;
        }
        if (user.getOfficeId() == null) {
            return false;
        }
        return officeRepository.findById(user.getOfficeId())
                .map(Office::isEnabled)
                .orElse(false);
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
