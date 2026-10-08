package br.com.ged.config;

import br.com.ged.user.Role;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JwtServiceTest {

    private static final String SECRET = "segredo-de-teste-com-no-minimo-32-caracteres!!";

    private String signedToken(Map<String, Object> claims) {
        return new JwtService(SECRET, 720).sign(claims);
    }

    @Test
    void tokenSemRoleRetornaVazioSemLancarErro() {
        Map<String, Object> claimsSomenteInternos = new LinkedHashMap<>();
        claimsSomenteInternos.put("key", "11-v3");
        claimsSomenteInternos.put("status", 1);
        claimsSomenteInternos.put("users", java.util.List.of("maria"));

        Optional<JwtService.JwtPrincipal> parsed = new JwtService(SECRET, 720)
                .parse(signedToken(claimsSomenteInternos));

        assertTrue(parsed.isEmpty());
    }

    @Test
    void tokenDeUsuarioAutenticaComPerfil() {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("sub", "admin");
        claims.put("role", "ADMIN");

        Optional<JwtService.JwtPrincipal> parsed = new JwtService(SECRET, 720).parse(signedToken(claims));

        assertEquals(Optional.of(new JwtService.JwtPrincipal("admin", Role.ADMIN)), parsed);
    }

    @Test
    void tokenInexistenteOuCorrompidoRetornaVazio() {
        JwtService jwtService = new JwtService(SECRET, 720);

        assertTrue(jwtService.parse("token-invalido").isEmpty());
        assertTrue(jwtService.parse(new JwtService("outro-segredo-que-e-bem-diferente-32car", 720)
                .sign(Map.of("sub", "admin", "role", "ADMIN"))).isEmpty());
    }
}
