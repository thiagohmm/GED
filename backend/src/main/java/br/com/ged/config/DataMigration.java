package br.com.ged.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Slf4j
@Order(1)
public class DataMigration implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        try {
            dropEnumCheckConstraints();
            jdbcTemplate.execute("ALTER TABLE users ALTER COLUMN role TYPE varchar(60)");
            int migratedUsers = jdbcTemplate.update("UPDATE users SET role = 'ADVOGADO' WHERE role = 'USER'");
            int migratedDocuments = jdbcTemplate.update("UPDATE documents SET status = 'EDITANDO' WHERE status IS NULL");
            int migratedVersions = jdbcTemplate.update("UPDATE document_versions SET status = 'EDITANDO' WHERE status IS NULL");
            log.info("Migracao de dados concluida: {} usuarios promovidos a ADVOGADO, {} documentos e {} versoes com status EDITANDO.",
                    migratedUsers, migratedDocuments, migratedVersions);
        } catch (Exception exception) {
            log.warn("Migracao de dados ignorada: {}", exception.getMessage());
        }
    }

    private void dropEnumCheckConstraints() {
        jdbcTemplate.execute("ALTER TABLE users DROP CONSTRAINT IF EXISTS users_role_check");
        jdbcTemplate.execute("ALTER TABLE documents DROP CONSTRAINT IF EXISTS documents_status_check");
        jdbcTemplate.execute("ALTER TABLE document_versions DROP CONSTRAINT IF EXISTS document_versions_status_check");
    }
}
