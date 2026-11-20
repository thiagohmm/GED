package br.com.ged.config;

import br.com.ged.user.Role;
import br.com.ged.user.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@Component
public class JwtService {

    public static final String TYPE_CLAIM = "typ";
    public static final String TOKEN_VERSION_CLAIM = "tv";
    public static final String TYPE_ACCESS = "ACCESS";
    public static final String TYPE_EDITOR_FILE = "EDITOR_FILE";
    public static final String TYPE_EDITOR_SESSION = "EDITOR_SESSION";
    public static final String TYPE_EDITOR_CONFIG = "EDITOR_CONFIG";

    private final SecretKey sessionSigningKey;
    private final SecretKey editorSigningKey;
    private final long expirationMinutes;

    @Autowired
    public JwtService(@Value("${ged.jwt.secret:}") String secret,
                      @Value("${ged.editor.jwt-secret:}") String editorSecret,
                      @Value("${ged.jwt.expiration-minutes:720}") long expirationMinutes) {
        validateSecret(secret, "GED_JWT_SECRET");
        validateSecret(editorSecret, "GED_EDITOR_JWT_SECRET");
        if (secret.equals(editorSecret)) {
            throw new IllegalStateException(
                    "GED_EDITOR_JWT_SECRET deve ser diferente de GED_JWT_SECRET para isolar os tokens do OnlyOffice.");
        }
        this.sessionSigningKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.editorSigningKey = Keys.hmacShaKeyFor(editorSecret.getBytes(StandardCharsets.UTF_8));
        this.expirationMinutes = expirationMinutes;
    }

    private static void validateSecret(String secret, String envName) {
        if (secret == null || secret.isBlank() || secret.length() < 32) {
            throw new IllegalStateException(
                    envName + " nao configurada ou com menos de 32 caracteres. Defina um segredo aleatorio "
                            + "(ex.: openssl rand -hex 32) em " + envName + ".");
        }
    }

    public String issueToken(User user) {
        Instant issuedAt = Instant.now();
        Instant expiration = issuedAt.plus(expirationMinutes, ChronoUnit.MINUTES);
        var builder = Jwts.builder()
                .subject(user.getUsername())
                .claim(TYPE_CLAIM, TYPE_ACCESS)
                .claim(TOKEN_VERSION_CLAIM, user.getTokenVersion())
                .claim("role", user.getRole().name());
        if (user.getOfficeId() != null) {
            builder.claim("officeId", user.getOfficeId());
        }
        return builder
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(expiration))
                .signWith(sessionSigningKey)
                .compact();
    }

    public Optional<JwtPrincipal> parse(String token) {
        try {
            Claims claims = verifySession(token);
            if (!TYPE_ACCESS.equals(claims.get(TYPE_CLAIM, String.class))) {
                return Optional.empty();
            }
            String subject = claims.getSubject();
            String roleClaim = claims.get("role", String.class);
            if (subject == null || subject.isBlank() || roleClaim == null) {
                return Optional.empty();
            }
            return Optional.of(new JwtPrincipal(subject, Role.valueOf(roleClaim)));
        } catch (JwtException | IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    /**
     * Valida o token de sessao (exigindo typ=ACCESS) e retorna o sujeito + versao do token.
     */
    public Optional<AccessTokenClaims> verifyAccessToken(String token) {
        try {
            Claims claims = verifySession(token);
            if (!TYPE_ACCESS.equals(claims.get(TYPE_CLAIM, String.class))) {
                return Optional.empty();
            }
            String subject = claims.getSubject();
            if (subject == null || subject.isBlank()) {
                return Optional.empty();
            }
            Number tv = claims.get(TOKEN_VERSION_CLAIM, Number.class);
            int tokenVersion = tv == null ? 0 : tv.intValue();
            return Optional.of(new AccessTokenClaims(subject, tokenVersion));
        } catch (JwtException | IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    public Optional<String> usernameOf(String token) {
        return verifyAccessToken(token).map(AccessTokenClaims::username);
    }

    public String signSession(Map<String, Object> claims) {
        return Jwts.builder().claims(claims).signWith(sessionSigningKey).compact();
    }

    public Claims verifySession(String token) {
        return Jwts.parser().verifyWith(sessionSigningKey).build().parseSignedClaims(token).getPayload();
    }

    public String signEditor(Map<String, Object> claims, Instant defaultExpiration) {
        Map<String, Object> copy = new LinkedHashMap<>(claims);
        copy.putIfAbsent("exp", Date.from(defaultExpiration));
        return Jwts.builder().claims(copy).signWith(editorSigningKey).compact();
    }

    public Claims verifyEditor(String token) {
        return Jwts.parser().verifyWith(editorSigningKey).build().parseSignedClaims(token).getPayload();
    }

    public Claims verifyEditor(String token, String expectedType) {
        Claims claims = verifyEditor(token);
        String actualType = claims.get(TYPE_CLAIM, String.class);
        if (!expectedType.equals(actualType)) {
            throw new JwtException("Tipo de token de editor invalido.");
        }
        return claims;
    }

    public record JwtPrincipal(String username, Role role) {
    }

    public record AccessTokenClaims(String username, int tokenVersion) {
    }
}
