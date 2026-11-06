package br.com.ged;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Garante que a migracao canonica (V1__init.sql) sobe do zero no Postgres e que a
 * validacao do Hibernate (ddl-auto=validate) aceita a schema coluna a coluna.
 * A simples subida do contexto ja executa Flyway + validate; as asserts fixam a schema.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.profiles.active=prod",
                "ged.jwt.secret=test-secret-integracao-0123456789-0123456789-0123456789",
                "ged.seed.super-admin-username=root",
                "ged.seed.super-admin-password=rootpass123",
                "ged.elasticsearch.enabled=false"
        })
class FlywaySchemaTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> postgres.getJdbcUrl());
        registry.add("spring.datasource.username", () -> postgres.getUsername());
        registry.add("spring.datasource.password", () -> postgres.getPassword());
    }

    @DynamicPropertySource
    static void backupProperties(DynamicPropertyRegistry registry) {
        registry.add("ged.storage-dir", () -> tempDir("ged-it-storage"));
        registry.add("ged.backup.dir", () -> tempDir("ged-it-backups"));
        registry.add("ged.backup.pg-host", () -> postgres.getHost());
        registry.add("ged.backup.pg-port", () -> postgres.getMappedPort(5432));
        registry.add("ged.backup.pg-database", () -> postgres.getDatabaseName());
        registry.add("ged.backup.pg-user", () -> postgres.getUsername());
        registry.add("ged.backup.pg-password", () -> postgres.getPassword());
        registry.add("ged.backup.maintenance-db", () -> "postgres");
    }

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void flywayAplicouAMigracaoInicial() throws SQLException {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT version, description, success FROM flyway_schema_history WHERE version = '1'");
        assertEquals("1", row.get("version"));
        assertEquals("init", row.get("description"));
        assertEquals(1, ((Number) row.get("success")).intValue());
    }

    @Test
    void schemaContemAsTabelasMultiempresa() {
        assertTrue(tableExists("offices"));
        assertTrue(tableExists("users"));
        assertTrue(tableExists("projects"));
        assertTrue(tableExists("folders"));
        assertTrue(tableExists("documents"));
        assertTrue(tableExists("document_versions"));
        assertTrue(tableExists("reviews"));
        assertTrue(tableExists("audit_events"));
        assertNotNull(jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns WHERE table_name = 'users' AND column_name = 'office_id'",
                Long.class));
        assertNotNull(jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns WHERE table_name = 'projects' AND column_name = 'office_id'",
                Long.class));
    }

    @Test
    void seedDeProducaoCriouOSuperAdmin() {
        Long superAdmins = jdbc.queryForObject(
                "SELECT count(*) FROM users WHERE role = 'SUPER_ADMIN' AND office_id IS NULL", Long.class);
        assertEquals(1L, superAdmins);
    }

    private boolean tableExists(String name) {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public' AND table_name = ?",
                Long.class, name);
        return count != null && count > 0;
    }

    private static String tempDir(String prefix) {
        try {
            return Files.createTempDirectory(prefix).toString();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
