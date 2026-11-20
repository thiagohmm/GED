package br.com.ged;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Matriz de autorizacao multiempresa e regressao de seguranca:
 *  - SUPER_ADMIN (root): gestao de escritorios, usuarios de qualquer escritorio,
 *    auditoria, integridade e backup;
 *  - ADMIN de escritorio: ve/opera so o proprio escritorio, nao toca em outro,
 *    nao acessa endpoints globais, nao cria nem promove outro ADMIN/SUPER_ADMIN;
 *  - PROTOCOLADOR/REVISOR: operam so dentro do proprio escritorio; PROTOCOLADOR lista
 *    apenas advogados ativos do escritorio (/api/users/attorneys);
 *  - Troca de senha e desativacao de escritorio invalidam imediatamente os JWTs ativos;
 *  - Exclusao de documento com revisoes apaga em cascata sem erro de FK.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.profiles.active=prod",
                "ged.jwt.secret=test-secret-integracao-0123456789-0123456789-0123456789",
                "ged.editor.jwt-secret=test-editor-secret-integracao-0123456789-0123456789",
                "ged.seed.super-admin-username=root",
                "ged.seed.super-admin-password=rootpass123",
                "ged.elasticsearch.enabled=false"
        })
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AuthorizationMatrixTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> postgres.getJdbcUrl());
        registry.add("spring.datasource.username", () -> postgres.getUsername());
        registry.add("spring.datasource.password", () -> postgres.getPassword());
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("ged.storage-dir", () -> tempDir("ged-it-authz-storage"));
        registry.add("ged.backup.dir", () -> tempDir("ged-it-authz-backups"));
        registry.add("ged.backup.pg-host", () -> postgres.getHost());
        registry.add("ged.backup.pg-port", () -> postgres.getMappedPort(5432));
        registry.add("ged.backup.pg-database", () -> postgres.getDatabaseName());
        registry.add("ged.backup.pg-user", () -> postgres.getUsername());
        registry.add("ged.backup.pg-password", () -> postgres.getPassword());
        registry.add("ged.backup.maintenance-db", () -> "postgres");
    }

    @Autowired
    TestRestTemplate rest;

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    @Order(0)
    @SuppressWarnings("unchecked")
    void matriz() {
        String rootToken = login("root", "rootpass123");

        Long officeA = idOf(post("/api/admin/offices", rootToken, Map.of("name", "Escritorio A")));
        Long officeB = idOf(post("/api/admin/offices", rootToken, Map.of("name", "Escritorio B")));

        Long adminAId = idOf(post("/api/users", rootToken,
                Map.of("username", "admina", "password", "adminapass", "role", "ADMIN", "officeId", officeA)));
        String adminAToken = login("admina", "adminapass");

        Long adminBId = idOf(post("/api/users", rootToken,
                Map.of("username", "adminb", "password", "adminbpass", "role", "ADMIN", "officeId", officeB)));
        String adminBToken = login("adminb", "adminbpass");

        Long advAId = idOf(post("/api/users", adminAToken,
                Map.of("username", "adva", "password", "advapass", "role", "ADVOGADO", "officeId", officeA)));
        Long advBId = idOf(post("/api/users", adminBToken,
                Map.of("username", "advb", "password", "advbpass", "role", "ADVOGADO", "officeId", officeB)));

        post("/api/users", adminAToken,
                Map.of("username", "protoa", "password", "protoapass", "role", "PROTOCOLADOR", "officeId", officeA));
        String protoAToken = login("protoa", "protoapass");

        post("/api/users", adminAToken,
                Map.of("username", "revisora", "password", "revisapass", "role", "REVISOR", "officeId", officeA));
        String revisorAToken = login("revisora", "revisapass");

        Long projectA = idOf(post("/api/projects", adminAToken, Map.of("name", "Processo A", "officeId", officeA)));
        Long projectB = idOf(post("/api/projects", adminBToken, Map.of("name", "Processo B", "officeId", officeB)));

        // Pastas raiz com mesmo nome em escritorios distintos sao isoladas e permitidas
        Long folderA = idOf(post("/api/folders", adminAToken,
                Map.of("name", "Contratos", "projectId", projectA, "assignedAttorneyId", advAId)));
        Long folderB = idOf(post("/api/folders", adminBToken,
                Map.of("name", "Contratos", "projectId", projectB, "assignedAttorneyId", advBId)));
        long documentA = upload(folderA, adminAToken, "contrato-a.png");
        long documentB = upload(folderB, adminBToken, "contrato-b.png");

        // --- SUPER_ADMIN: escopo global ---
        assertEquals(200, get("/api/admin/offices", rootToken));
        assertEquals(200, get("/api/admin/audit", rootToken));
        assertEquals(200, get("/api/admin/integrity", rootToken));
        assertEquals(200, get("/api/users", rootToken));
        List<?> rootProjects = getEntity("/api/projects", rootToken, List.class).getBody();
        assertEquals(2, rootProjects.size());

        // --- ADMIN A: sem gestao global, so o proprio escritorio ---
        assertEquals(403, get("/api/admin/offices", adminAToken));
        assertEquals(403, get("/api/admin/audit", adminAToken));
        assertEquals(403, get("/api/admin/integrity", adminAToken));
        List<?> adminAprojects = getEntity("/api/projects", adminAToken, List.class).getBody();
        assertEquals(1, adminAprojects.size());
        assertEquals(200, get("/api/documents/" + documentA, adminAToken));
        assertEquals(403, get("/api/documents/" + documentB, adminAToken));

        // --- ADMIN B: nao ve dados de A ---
        assertEquals(403, get("/api/documents/" + documentA, adminBToken));
        List<?> adminBprojects = getEntity("/api/projects", adminBToken, List.class).getBody();
        assertEquals(1, adminBprojects.size());

        // --- protecoes de papel e anti-escalada ---
        expect("/api/users/" + adminBId, HttpMethod.PATCH, adminAToken, Map.of("enabled", true), 403);
        expect("/api/users/" + adminAId, HttpMethod.PATCH, adminAToken, Map.of("enabled", false), 400);
        expect("/api/users/" + adminAId, HttpMethod.PATCH, adminAToken, Map.of("role", "PROTOCOLADOR"), 400);
        expect("/api/users/" + advAId, HttpMethod.PATCH, adminAToken, Map.of("role", "ADMIN"), 403);
        expect("/api/users/" + advAId, HttpMethod.PATCH, adminAToken, Map.of("role", "SUPER_ADMIN"), 400);

        expect("/api/users", HttpMethod.POST, adminAToken,
                Map.of("username", "novoa", "password", "novoa123", "role", "PROTOCOLADOR", "officeId", officeA), 201);
        expect("/api/users", HttpMethod.POST, adminAToken,
                Map.of("username", "outroadmin", "password", "admin123", "role", "ADMIN", "officeId", officeA), 403);
        expect("/api/users", HttpMethod.POST, adminAToken,
                Map.of("username", "invasorb", "password", "invasor123", "role", "PROTOCOLADOR", "officeId", officeB), 409);
        expect("/api/users", HttpMethod.POST, adminAToken,
                Map.of("username", "falsoroot", "password", "falso123", "role", "SUPER_ADMIN", "officeId", officeA), 403);

        // --- PROTOCOLADOR ---
        assertEquals(403, get("/api/users", protoAToken));
        assertEquals(200, get("/api/users/attorneys", protoAToken));
        Long subfolderA = idOf(post("/api/folders", protoAToken, Map.of("name", "Subpastas", "parentId", folderA)));
        long subDocId = upload(subfolderA, protoAToken, "sub.png");
        assertTrue(subDocId > 0);
        expect("/api/documents/" + documentA + "/status", HttpMethod.POST, protoAToken,
                Map.of("to", "EM_REVISAO", "note", "enviar para revisao"), 200);

        // --- REVISOR + exclusao de documento com revisao ---
        assertEquals(200, get("/api/reviews/queue", revisorAToken));
        assertEquals(403, get("/api/users", revisorAToken));
        List<Map<String, Object>> reviews = getEntity("/api/documents/" + documentA + "/reviews", revisorAToken, List.class).getBody();
        long reviewId = ((Number) reviews.get(0).get("reviewId")).longValue();
        expect("/api/reviews/" + reviewId + "/decide", HttpMethod.POST, revisorAToken,
                Map.of("verdict", "PENDENTE"), 400);
        expect("/api/reviews/" + reviewId + "/decide", HttpMethod.POST, revisorAToken,
                Map.of("verdict", "CORRECOES_SOLICITADAS", "summary", "ajustar"), 200);
        expect("/api/documents/" + documentA, HttpMethod.DELETE, adminAToken, null, 204);

        // --- Troca de senha invalida token antigo ---
        expect("/api/account/password", HttpMethod.PATCH, protoAToken,
                Map.of("currentPassword", "protoapass", "newPassword", "novasenhaproto"), 204);
        assertNotEquals(200, get("/api/projects", protoAToken));
        String newProtoToken = login("protoa", "novasenhaproto");
        assertEquals(200, get("/api/projects", newProtoToken));

        // --- Desativar escritorio bloqueia sessoes ativas ---
        expect("/api/admin/offices/" + officeB, HttpMethod.PATCH, rootToken, Map.of("enabled", false), 200);
        assertNotEquals(200, get("/api/projects", adminBToken));

        // --- sem token ---
        assertNotEquals(200, get("/api/projects", null));
    }

    private static String tempDir(String prefix) {
        try {
            return Files.createTempDirectory(prefix).toString();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    @SuppressWarnings("unchecked")
    private String login(String username, String password) {
        ResponseEntity<Map> response = rest.postForEntity("/api/auth/login",
                Map.of("username", username, "password", password), Map.class);
        assertEquals(200, response.getStatusCode().value(), "Login de " + username + " falhou");
        return (String) response.getBody().get("token");
    }

    private int get(String path, String token) {
        return getEntity(path, token, String.class).getStatusCode().value();
    }

    private <T> ResponseEntity<T> getEntity(String path, String token, Class<T> type) {
        return rest.exchange(path, HttpMethod.GET, authed(token), type);
    }

    private void expect(String path, HttpMethod method, String token, Object body, int expectedStatus) {
        HttpEntity<String> entity = body == null ? authed(token) : jsonEntity(token, body);
        ResponseEntity<String> response = rest.exchange(path, method, entity, String.class);
        assertEquals(expectedStatus, response.getStatusCode().value(),
                method + " " + path + " esperado " + expectedStatus + ", veio " + response.getStatusCode()
                        + ": " + response.getBody());
    }

    @SuppressWarnings("unchecked")
    private Map<?, ?> post(String path, String token, Object body) {
        ResponseEntity<Map> response = rest.exchange(path, HttpMethod.POST, jsonEntity(token, body), Map.class);
        assertTrue(response.getStatusCode().is2xxSuccessful(),
                "POST " + path + " falhou: " + response.getStatusCode() + " " + response.getBody());
        return response.getBody();
    }

    private long upload(Long folderId, String token, String filename) {
        HttpHeaders multipart = new HttpHeaders();
        multipart.setContentType(MediaType.MULTIPART_FORM_DATA);
        multipart.setBearerAuth(token);
        LinkedMultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", new ByteArrayResource(pngBytes()) {
            @Override
            public String getFilename() {
                return filename;
            }
        });
        ResponseEntity<Map> response = rest.exchange(
                "/api/folders/" + folderId + "/documents", HttpMethod.POST,
                new HttpEntity<>(form, multipart), Map.class);
        assertEquals(201, response.getStatusCode().value(),
                "Upload falhou: " + response.getStatusCode() + " " + response.getBody());
        return ((Number) response.getBody().get("id")).longValue();
    }

    private long idOf(Map<?, ?> body) {
        return ((Number) body.get("id")).longValue();
    }

    private HttpEntity<String> authed(String token) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return new HttpEntity<>(headers);
    }

    private HttpEntity<String> jsonEntity(String token, Object body) {
        try {
            HttpHeaders headers = new HttpHeaders();
            if (token != null) {
                headers.setBearerAuth(token);
            }
            headers.setContentType(MediaType.APPLICATION_JSON);
            return new HttpEntity<>(JSON.writeValueAsString(body), headers);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static byte[] pngBytes() {
        return new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
                0, 0, 0, 0x0D, 0x49, 0x48, 0x44, 0x52, 0, 0, 0, 0x01, 0, 0, 0, 0x01,
                (byte) 0x08, 0x06, 0, 0, 0, (byte) 0xF1, (byte) 0x81, (byte) 0xC6, 0x69,
                0, 0, 0, 0x0D, 0x49, 0x44, 0x41, 0x54, 0x78, (byte) 0x9C, 0x63, 0, 0, 0, 0x02, 0x00,
                0x01, 0x02, (byte) 0x83, 0x60, (byte) 0x65, 0, 0, 0, 0, 0, 0x49, 0x45, 0x4E, 0x44,
                (byte) 0xAE, 0x42, 0x60, (byte) 0x82};
    }
}
