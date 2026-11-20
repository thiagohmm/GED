package br.com.ged.config;

import br.com.ged.user.Role;
import br.com.ged.user.User;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JwtServiceTest {

    private static final String SECRET = "segredo-de-teste-com-no-minimo-32-caracteres!!";
    private static final String EDITOR_SECRET = "outro-segredo-exclusivo-do-editor-min-32-chars!";

    private JwtService newService() {
        return new JwtService(SECRET, EDITOR_SECRET, 720);
    }

    @Test
    void tokenSemRoleOuSemTypAccessRetornaVazioSemLancarErro() {
        JwtService jwt = newService();
        Map<String, Object> claimsSomenteInternos = new LinkedHashMap<>();
        claimsSomenteInternos.put("key", "11-v3");
        claimsSomenteInternos.put("status", 1);
        claimsSomenteInternos.put("users", java.util.List.of("maria"));

        assertTrue(jwt.parse(jwt.signSession(claimsSomenteInternos)).isEmpty());

        Map<String, Object> semTyp = new LinkedHashMap<>();
        semTyp.put("sub", "admin");
        semTyp.put("role", "ADMIN");
        assertTrue(jwt.usernameOf(jwt.signSession(semTyp)).isEmpty());
    }

    @Test
    void tokenDeUsuarioEmitidoAutenticaComPerfilEVersao() {
        JwtService jwt = newService();
        User user = new User("admin", "hash", Role.ADMIN, true, 5L);
        user.setTokenVersion(3);

        String token = jwt.issueToken(user);

        assertEquals(Optional.of(new JwtService.JwtPrincipal("admin", Role.ADMIN)), jwt.parse(token));
        assertEquals(Optional.of(new JwtService.AccessTokenClaims("admin", 3)), jwt.verifyAccessToken(token));
    }

    @Test
    void tokenDoEditorNaoEhAceitoComoSessaoDeUsuarioENemViceVersa() {
        JwtService jwt = newService();
        String editorFileToken = jwt.signEditor(
                Map.of(JwtService.TYPE_CLAIM, JwtService.TYPE_EDITOR_FILE, "sub", "admin", "versionId", 10L),
                Instant.now().plusSeconds(3600));

        assertTrue(jwt.usernameOf(editorFileToken).isEmpty());
        assertTrue(jwt.parse(editorFileToken).isEmpty());

        User user = new User("admin", "hash", Role.ADMIN, true, 1L);
        String accessToken = jwt.issueToken(user);
        assertThrows(RuntimeException.class,
                () -> jwt.verifyEditor(accessToken, JwtService.TYPE_EDITOR_FILE));
    }

    @Test
    void rejeitaSegredosIguaisOuCurtos() {
        assertThrows(IllegalStateException.class, () -> new JwtService(SECRET, SECRET, 720));
        assertThrows(IllegalStateException.class, () -> new JwtService("curto", EDITOR_SECRET, 720));
    }

    @Test
    void tokenInexistenteOuCorrompidoRetornaVazio() {
        JwtService jwtService = newService();

        assertTrue(jwtService.parse("token-invalido").isEmpty());
        JwtService outro = new JwtService(
                "outro-segredo-que-e-bem-diferente-32car",
                "mais-um-segredo-distinto-de-editor-32car",
                720);
        User user = new User("admin", "hash", Role.ADMIN, true, 1L);
        assertTrue(jwtService.parse(outro.issueToken(user)).isEmpty());
    }
}
