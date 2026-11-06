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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Cenario completo de backup/restore (plan WS5):
 *  1. cria escritorio + projeto + pasta + upload real;
 *  2. cria backup (pg_dump -Fc + tar do storage + manifest + SHA256SUMS);
 *  3. simula desastre: apaga o storage e derruba o schema do banco;
 *  4. restaura pelo bundle e confirma usuarios, projeto, documento, arquivo,
 *     integridade e busca reindexada;
 *  5. bundle corrompido e rejeitado (hash).
 * Requer pg_dump/pg_restore/tar na maquina host; caso ausentes, o teste e pulado.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.profiles.active=prod",
                "ged.jwt.secret=test-secret-integracao-0123456789-0123456789-0123456789",
                "ged.seed.super-admin-username=root",
                "ged.seed.super-admin-password=rootpass123",
                "ged.elasticsearch.enabled=true"
        })
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BackupRestoreIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static ElasticsearchContainer elastic = new ElasticsearchContainer("docker.elastic.co/elasticsearch/elasticsearch:8.15.3");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> postgres.getJdbcUrl());
        registry.add("spring.datasource.username", () -> postgres.getUsername());
        registry.add("spring.datasource.password", () -> postgres.getPassword());
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("ged.storage-dir", () -> STORAGE_DIR);
        registry.add("ged.backup.dir", () -> BACKUP_DIR);
        registry.add("ged.backup.pg-host", () -> postgres.getHost());
        registry.add("ged.backup.pg-port", () -> postgres.getMappedPort(5432));
        registry.add("ged.backup.pg-database", () -> postgres.getDatabaseName());
        registry.add("ged.backup.pg-user", () -> postgres.getUsername());
        registry.add("ged.backup.pg-password", () -> postgres.getPassword());
        registry.add("ged.backup.maintenance-db", () -> "postgres");
        registry.add("ged.elasticsearch.uris", () -> elastic.getHttpHostAddress());
    }

    @Autowired
    TestRestTemplate rest;

    private static final ObjectMapper JSON = new ObjectMapper();

    static final String STORAGE_DIR = tempDir("ged-it-br-storage");
    static final String BACKUP_DIR = tempDir("ged-it-br-backups");

    private static String rootToken;
    private static Long officeId;
    private static Long projectId;
    private static Long folderId;
    private static String backupName;

    @Test
    @Order(0)
    void criacao() {
        rootToken = login("root", "rootpass123");
        Map<?, ?> office = post("/api/admin/offices", rootToken, Map.of("name", "Escritorio Backup"));
        officeId = idOf(office);
        projectId = idOf(post("/api/projects", rootToken, Map.of("name", "Processo Backup", "officeId", officeId)));
        folderId = idOf(post("/api/folders", rootToken, Map.of("name", "Pasta", "projectId", projectId)));
        upload(folderId, rootToken, "documento-original.png");
        List<?> documents = getEntity("/api/folders/" + folderId + "/documents", rootToken, List.class).getBody();
        assertEquals(1, documents.size());
    }

    @Test
    @Order(1)
    void backup() {
        assumeTrue(hasTool("pg_dump") && hasTool("pg_restore") && hasTool("tar"),
                "pg_dump/pg_restore/tar ausentes no host");
        Map<?, ?> created = post("/api/admin/backups", rootToken, Map.of());
        backupName = (String) created.get("name");
        assertNotNull(backupName);
        Path bundle = Path.of(BACKUP_DIR, backupName);
        assertTrue(Files.exists(bundle.resolve("database.dump")));
        assertTrue(Files.exists(bundle.resolve("storage.tar.gz")));
        assertTrue(Files.exists(bundle.resolve("SHA256SUMS")));
        assertTrue(Files.exists(bundle.resolve("manifest.json")));
    }

    @Test
    @Order(2)
    void desastre() throws Exception {
        assumeTrue(hasTool("pg_dump"), "pg_dump ausente no host");
        // apaga o storage
        if (Files.exists(Path.of(STORAGE_DIR))) {
            for (Path file : Files.walk(Path.of(STORAGE_DIR)).sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(file);
            }
        }
        // derruba o schema do banco (simula perda de dados)
        String url = "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(5432) + "/" + postgres.getDatabaseName();
        try (var conn = DriverManager.getConnection(url, postgres.getUsername(), postgres.getPassword())) {
            try (var stmt = conn.createStatement()) {
                stmt.executeUpdate("DROP SCHEMA public CASCADE");
                stmt.executeUpdate("CREATE SCHEMA public");
            }
        }
    }

    @Test
    @Order(3)
    void restore() throws Exception {
        assumeTrue(hasTool("pg_dump"), "pg_dump ausente no host");
        ResponseEntity<String> response = rest.exchange("/api/admin/restore", HttpMethod.POST,
                jsonEntity(rootToken, Map.of("backup", backupName)), String.class);
        assertEquals(200, response.getStatusCode().value(), "Restore falhou: " + response.getBody());

        // usuarios e estrutura voltaram
        String token = login("root", "rootpass123");
        List<?> projects = getEntity("/api/projects", token, List.class).getBody();
        assertEquals(1, projects.size());
        Long restoredProject = idOf(projects.get(0));
        Map<?, ?> project = getEntity("/api/projects/" + restoredProject, token, Map.class).getBody();
        assertNotNull(project);

        // documento e arquivo original voltaram
        List<?> documents = documentsOf(token, restoredProject);
        assertEquals(1, documents.size());
        Long restoredDoc = idOf(documents.get(0));
        List<?> versions = getEntity("/api/documents/" + restoredDoc + "/versions", token, List.class).getBody();
        assertEquals(1, versions.size());
        Long restoredVersion = idOf(versions.get(0));
        ResponseEntity<byte[]> download = getEntity("/api/versions/" + restoredVersion + "/download",
                token, byte[].class);
        assertEquals(200, download.getStatusCode().value());
        assertEquals(pngBytes(), download.getBody());

        // integridade: 100% ok apos restore
        ResponseEntity<Map> integrity = getEntity("/api/admin/integrity", token, Map.class);
        assertEquals(200, integrity.getStatusCode().value());
        assertEquals(true, integrity.getBody().get("allOk"));

        // busca reindexada
        String body = getEntity("/api/search?q=documento", token, String.class).getBody();
        assertTrue(body != null && body.contains("documento-original"), "Busca nao achou o documento reindexado: " + body);
    }

    @Test
    @Order(4)
    void bundleCorrompidoERejeitado() throws Exception {
        assumeTrue(hasTool("pg_dump"), "pg_dump ausente no host");
        // novo backup limpo para corromper em seguida
        Map<?, ?> created = post("/api/admin/backups", rootToken, Map.of());
        String name = (String) created.get("name");
        Path dump = Path.of(BACKUP_DIR, name, "database.dump");
        byte[] bytes = Files.readAllBytes(dump);
        bytes[10] ^= 0x01; // corrompe 1 byte
        Files.write(dump, bytes);

        ResponseEntity<String> verify = rest.postForEntity("/api/admin/backups/" + name + "/verify",
                authed(rootToken), String.class);
        assertEquals(400, verify.getStatusCode().value(), "verify deveria falhar para bundle corrompido");

        ResponseEntity<String> restore = rest.exchange("/api/admin/restore", HttpMethod.POST,
                jsonEntity(rootToken, Map.of("backup", name)), String.class);
        assertEquals(400, restore.getStatusCode().value(), "restore deveria falhar para bundle corrompido");

        // o backup original continua intacto e usavel
        ResponseEntity<String> ok = rest.exchange("/api/admin/restore", HttpMethod.POST,
                jsonEntity(rootToken, Map.of("backup", backupName)), String.class);
        assertEquals(200, ok.getStatusCode().value(), "Restore do bundle valido falhou: " + ok.getBody());
    }

    // ------------------------------------------------------------- helpers

    private List<?> documentsOf(String token, Long projectId) {
        List<?> tree = getEntity("/api/folders/tree", token, List.class).getBody();
        assertNotNull(tree);
        Long folder = findFolder(tree, projectId);
        assertNotNull(folder, "Nenhuma pasta restaurada no projeto " + projectId);
        return getEntity("/api/folders/" + folder + "/documents", token, List.class).getBody();
    }

    @SuppressWarnings("unchecked")
    private static Long findFolder(List<?> nodes, Long projectId) {
        for (Object node : nodes) {
            Map<?, ?> map = (Map<?, ?>) node;
            Object project = map.get("projectId");
            if (project != null && project.equals(projectId)) {
                return ((Number) map.get("id")).longValue();
            }
            if (map.get("children") instanceof List<?> childList) {
                Long found = findFolder((List<?>) childList, projectId);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private <T> ResponseEntity<T> getEntity(String path, String token, Class<T> type) {
        return rest.exchange(path, HttpMethod.GET, authed(token), type);
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

    @SuppressWarnings("unchecked")
    private String login(String username, String password) {
        ResponseEntity<Map> response = rest.postForEntity("/api/auth/login",
                Map.of("username", username, "password", password), Map.class);
        assertEquals(200, response.getStatusCode().value(), "Login de " + username + " falhou");
        return (String) response.getBody().get("token");
    }

    @SuppressWarnings("unchecked")
    private Map<?, ?> post(String path, String token, Object body) {
        ResponseEntity<Map> response = rest.exchange(path, HttpMethod.POST, jsonEntity(token, body), Map.class);
        assertTrue(response.getStatusCode().is2xxSuccessful(),
                "POST " + path + " falhou: " + response.getStatusCode() + " " + response.getBody());
        return response.getBody();
    }

    private long idOf(Object body) {
        return ((Number) ((Map<?, ?>) body).get("id")).longValue();
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

    private static boolean hasTool(String tool) {
        String path = System.getenv("PATH");
        if (path == null) {
            return false;
        }
        String os = System.getProperty("os.name").toLowerCase();
        String exe = os.contains("win") ? tool + ".exe" : tool;
        return java.util.Arrays.stream(path.split(java.io.File.separator))
                .map(dir -> Path.of(dir, exe))
                .anyMatch(Files::isExecutable);
    }

    private static String tempDir(String prefix) {
        try {
            return Files.createTempDirectory(prefix).toString();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static byte[] pngBytes() {
        // PNG 1x1 minimo
        return new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
                0, 0, 0, 0x0D, 0x49, 0x48, 0x44, 0x52, 0, 0, 0, 0x01, 0, 0, 0, 0x01,
                (byte) 0x08, 0x06, 0, 0, 0, (byte) 0xF1, (byte) 0x81, (byte) 0xC6, 0x69,
                0, 0, 0, 0x0D, 0x49, 0x44, 0x41, 0x54, 0x78, (byte) 0x9C, 0x63, 0, 0, 0, 0x02, 0x00,
                0x01, 0x02, (byte) 0x83, 0x60, (byte) 0x65, 0, 0, 0, 0, 0, 0x49, 0x45, 0x4E, 0x44,
                (byte) 0xAE, 0x42, 0x60, (byte) 0x82};
    }
}
