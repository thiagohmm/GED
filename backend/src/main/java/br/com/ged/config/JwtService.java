package br.com.ged.config;

import br.com.ged.user.Role;
import br.com.ged.user.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.Optional;
import java.util.Map;

@Component
public class JwtService {

    private final SecretKey signingKey;
    private final long expirationMinutes;

    public JwtService(@Value("${ged.jwt.secret}") String secret,
                      @Value("${ged.jwt.expiration-minutes:720}") long expirationMinutes) {
        this.signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expirationMinutes = expirationMinutes;
    }

    public String issueToken(User user) {
        Instant issuedAt = Instant.now();
        Instant expiration = issuedAt.plus(expirationMinutes, ChronoUnit.MINUTES);
        return Jwts.builder()
                .subject(user.getUsername())
                .claim("role", user.getRole().name())
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(expiration))
                .signWith(signingKey)
                .compact();
    }

    public Optional<JwtPrincipal> parse(String token) {
        try {
            Claims claims = verify(token);
            String roleClaim = claims.get("role", String.class);
            if (roleClaim == null) {
                return Optional.empty();
            }
            return Optional.of(new JwtPrincipal(claims.getSubject(), Role.valueOf(roleClaim)));
        } catch (JwtException | IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    public String sign(Map<String, Object> claims) {
        return Jwts.builder().claims(claims).signWith(signingKey).compact();
    }

    public Claims verify(String token) {
        return Jwts.parser().verifyWith(signingKey).build().parseSignedClaims(token).getPayload();
    }

    public record JwtPrincipal(String username, Role role) {
    }
}
