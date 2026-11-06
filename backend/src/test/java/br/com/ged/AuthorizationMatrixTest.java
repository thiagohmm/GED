package br.com.ged;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Matriz de autorizacao multiempresa:
 *  - SUPER_ADMIN (root): gestao de escritorios, usuarios de qualquer escritorio,
 *    auditoria, integridade e backup;
 *  - ADMIN de escritorio: ve/some so o proprio escritorio, nao toca em outro,
 *    nao acessa endpoints globais, nao altera outro ADMIN, nao se desativa/demove;
 *  - PROTOCOLADOR/REVISOR: operam so dentro do proprio escritorio e sem gestao de usuarios.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.profiles.active=prod",
                "ged.jwt.secret=test-secret-integracao-0123456789-0123456789-0123456789",
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

    private static String rootToken;
    private static String adminAToken;
    private static String adminBToken;
    private static Long officeA;
    private static Long officeB;
    private static Long adminAId;
    private static Long adminBId;
    private static Long projectA;
    private static Long folderA;
    private static Long documentA;
    private static Long documentB;

    @Test
    @Order(0)
    @SuppressWarnings("unchecked")
    void matriz() {
        // --- root (SUPER_ADMIN) cria a estrutura ---
        rootToken = login("root", "rootpass123");

        Map<?, ?> createdA = post("/api/admin/offices", rootToken, Map.of("name", "Escritorio A"));
        officeA = idOf(createdA);
        Map<?, ?> createdB = post("/api/admin/offices", rootToken, Map.of("name", "Escritorio B"));
        officeB = idOf(createdB);

        Map<?, ?> adminA = post("/api/users", rootToken,
                Map.of("username", "admina", "password", "adminapass", "role", "ADMIN", "officeId", officeA));
        adminAId = idOf(adminA);
        adminAToken = login("admina", "adminapass");

        Map<?, ?> adminB = post("/api/users", rootToken,
                Map.of("username", "adminb", "password", "adminbpass", "role", "ADMIN", "officeId", officeB));
        adminBId = idOf(adminB);
        adminBToken = login("adminb", "adminbpass");

        post("/api/users", rootToken,
                Map.of("username", "protoa", "password", "protoapass", "role", "PROTOCOLADOR", "officeId", officeA));
        String protoAToken = login("protoa", "protoapass");
        post("/api/users", rootToken,
                Map.of("username", "revisora", "password", "revisapass", "role", "REVISOR", "officeId", officeA));
        String revisorAToken = login("revisora", "revisapass");

        // projetos por escritorio
        projectA = idOf(post("/api/projects", adminAToken, Map.of("name", "Processo A", "officeId", officeA)));
        Long projectB = idOf(post("/api/projects", adminBToken, Map.of("name", "Processo B", "officeId", officeB)));

        // pastas e documentos em cada escritorio
        folderA = idOf(post("/api/folders", adminAToken, Map.of("name", "Contratos", "projectId", projectA)));
        Long folderB = idOf(post("/api/folders", adminBToken, Map.of("name", "Contratos", "projectId", projectB)));
        documentA = upload(folderA, adminAToken, "contrato-a.png");
        documentB = upload(folderB, adminBToken, "contrato-b.png");

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

        // --- protecoes de papel ---
        expect("/api/users/" + adminBId, HttpMethod.PATCH, adminAToken, Map.of("enabled", true), 403);
        expect("/api/users/" + adminAId, HttpMethod.PATCH, adminAToken, Map.of("enabled", false), 400);
        expect("/api/users/" + adminAId, HttpMethod.PATCH, adminAToken, Map.of("role", "PROTOCOLADOR"), 400);

        expect("/api/users", HttpMethod.POST, adminAToken,
                Map.of("username", "novoa", "password", "novoa123", "role", "PROTOCOLADOR", "officeId", officeA), 201);
        expect("/api/users", HttpMethod.POST, adminAToken,
                Map.of("username", "invasorb", "password", "invasor123", "role", "PROTOCOLADOR", "officeId", officeB), 403);
        expect("/api/users", HttpMethod.POST, adminAToken,
                Map.of("username", "falsoroot", "password", "falso123", "role", "SUPER_ADMIN", "officeId", officeA), 403);

        // --- PROTOCOLADOR ---
        assertEquals(403, get("/api/users", protoAToken));
        Long subfolderA = idOf(post("/api/folders", protoAToken, Map.of("name", "Subpastas", "parentId", folderA)));
        assertEquals(subfolderA, upload(subfolderA, protoAToken, "sub.png"));
        expect("/api/documents/" + documentA + "/status", HttpMethod.POST, protoAToken,
                Map.of("status", "PRONTO_PARA_PROTOCOLO", "note", "ok"), 200);

        // --- REVISOR ---
        assertEquals(200, get("/api/reviews/queue", revisorAToken));
        assertEquals(403, get("/api/users", revisorAToken));

        // --- sem token ---
        assertEquals(401, get("/api/projects", null));
    }

    // ------------------------------------------------------------- helpers

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
        HttpEntity<String> entity = jsonEntity(token, body);
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
        // PNG 1x1 minimico
        return new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
                0, 0, 0, 0x0D, 0x49, 0x48, 0x44, 0x52, 0, 0, 0, 0x01, 0, 0, 0, 0x01,
                (byte) 0x08, 0x06, 0, 0, 0, (byte) 0xF1, (byte) 0x81, (byte) 0xC6, 0x69,
                0, 0, 0, 0x0D, 0x49, 0x44, 0x41, 0x54, 0x78, (byte) 0x9C, 0x63, 0, 0, 0, 0x02, 0x00,
                0x01, 0x02, (byte) 0x83, 0x60, (byte) 0x65, 0, 0, 0, 0, 0, 0x49, 0x45, 0x4E, 0x44,
                (byte) 0xAE, 0x42, 0x60, (byte) 0x82};
    }
}
