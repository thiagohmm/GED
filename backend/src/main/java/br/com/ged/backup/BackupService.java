package br.com.ged.backup;

import br.com.ged.audit.AuditEventService;
import br.com.ged.document.DocumentRepository;
import br.com.ged.document.DocumentVersionRepository;
import br.com.ged.document.StorageService;
import br.com.ged.exception.BusinessException;
import br.com.ged.integrity.IntegrityReport;
import br.com.ged.integrity.IntegrityService;
import br.com.ged.office.OfficeRepository;
import br.com.ged.search.ReindexService;
import br.com.ged.user.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Backup e restore do GED:
 *  - backup: dump do Postgres (pg_dump -Fc), tar.gz da storage, manifest.json e SHA256SUMS;
 *  - restore: valida hashes, recria o banco (drop/create pelo banco de manutencao),
 *    pg_restore, extrai a storage, verifica integridade dos arquivos e reindexa o ES.
 *
 * Os binarios pg_dump/pg_restore devem estar acessiveis no host (ou via ged.backup.pg-bin-dir).
 */
@Service
@Slf4j
public class BackupService {

    private static final DateTimeFormatter BUNDLE_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss").withZone(ZoneOffset.UTC);

    private final BackupProperties properties;
    private final StorageService storageService;
    private final UserRepository userRepository;
    private final OfficeRepository officeRepository;
    private final DocumentRepository documentRepository;
    private final DocumentVersionRepository documentVersionRepository;
    private final AuditEventService auditEventService;
    private final IntegrityService integrityService;
    private final ReindexService reindexService;
    private final ObjectMapper objectMapper;

    public BackupService(BackupProperties properties,
                         StorageService storageService,
                         UserRepository userRepository,
                         OfficeRepository officeRepository,
                         DocumentRepository documentRepository,
                         DocumentVersionRepository documentVersionRepository,
                         AuditEventService auditEventService,
                         IntegrityService integrityService,
                         ReindexService reindexService,
                         ObjectMapper objectMapper) {
        this.properties = properties;
        this.storageService = storageService;
        this.userRepository = userRepository;
        this.officeRepository = officeRepository;
        this.documentRepository = documentRepository;
        this.documentVersionRepository = documentVersionRepository;
        this.auditEventService = auditEventService;
        this.integrityService = integrityService;
        this.reindexService = reindexService;
        this.objectMapper = objectMapper;
    }

    /** Cria um bundle de backup completo e retorna o nome da pasta gerada. */
    public String create() {
        validateDatabaseName(properties.getPgDatabase());
        Path backupRoot = Path.of(properties.getDir()).toAbsolutePath().normalize();
        Path bundle = backupRoot.resolve("ged-" + BUNDLE_FORMAT.format(Instant.now()));
        try {
            Files.createDirectories(bundle);

            Path dump = bundle.resolve("database.dump");
            runCommand(List.of(pgBinary("pg_dump"), "-Fc", "-f", dump.toString()), pgEnv());

            Path storageRoot = storageService.storageRoot();
            Path storageArchive = bundle.resolve("storage.tar.gz");
            runCommand(List.of("tar", "-czf", storageArchive.toString(),
                    "-C", storageRoot.getParent().toString(), storageRoot.getFileName().toString()), null);

            BackupManifest manifest = buildManifest();
            Path manifestFile = bundle.resolve("manifest.json");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(manifestFile.toFile(), manifest);

            writeChecksums(bundle, List.of(dump.getFileName().toString(),
                    storageArchive.getFileName().toString(), manifestFile.getFileName().toString()));

            auditEventService.record("BACKUP_CREATE", "BACKUP", bundle.getFileName().toString(),
                    AuditEventService.details("users", manifest.users(), "offices", manifest.offices(),
                            "documents", manifest.documents(), "versions", manifest.versions()));
            log.info("Backup criado em {}.", bundle);
            return bundle.getFileName().toString();
        } catch (IOException exception) {
            throw new UncheckedIOException("Falha ao criar o backup.", exception);
        }
    }

    /** Restaura um bundle: valida hashes, recria o banco, restaura storage, verifica integridade e reindexa. */
    public void restore(String bundleName) {
        Path bundle = resolveBundle(bundleName);
        List<String> failures = verifyHashes(bundle);
        if (!failures.isEmpty()) {
            throw new BusinessException("Hashes do backup nao conferem: " + String.join("; ", failures));
        }
        validateDatabaseName(properties.getPgDatabase());

        recreateDatabase();

        try {
            runCommand(List.of(pgBinary("pg_restore"), "-d", properties.getPgDatabase(),
                    bundle.resolve("database.dump").toString()), pgEnv());
        } catch (BusinessException exception) {
            throw new BusinessException("Falha ao restaurar o banco de dados: " + exception.getMessage());
        }

        Path storageRoot = storageService.storageRoot();
        try {
            deleteContents(storageRoot);
            Files.createDirectories(storageRoot);
            runCommand(List.of("tar", "-xzf", bundle.resolve("storage.tar.gz").toString(),
                    "-C", storageRoot.getParent().toString()), null);
        } catch (IOException exception) {
            throw new UncheckedIOException("Falha ao restaurar a storage de arquivos.", exception);
        }

        IntegrityReport integrity = integrityService.check();
        if (!integrity.allOk()) {
            throw new BusinessException("Restore abortado: integridade da storage falhou em "
                    + integrity.issues().size() + " versao(es).");
        }

        boolean reindexed = reindexService.reindexAll();
        log.info("Restore de {} concluido (reindexacao: {}).", bundleName, reindexed ? "ok" : "pulada");
        auditEventService.record("RESTORE", "BACKUP", bundleName,
                AuditEventService.details("reindex", reindexed));
    }

    /** Valida apenas os SHA256SUMS do bundle; retorna a lista de falhas (vazia = ok). */
    public List<String> verify(String bundleName) {
        return verifyHashes(resolveBundle(bundleName));
    }

    public List<BackupInfo> list() {
        Path backupRoot = Path.of(properties.getDir()).toAbsolutePath().normalize();
        if (!Files.isDirectory(backupRoot)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(backupRoot)) {
            return entries
                    .filter(Files::isDirectory)
                    .filter(entry -> entry.getFileName().toString().startsWith("ged-"))
                    .sorted(Comparator.comparing((Path entry) -> entry.getFileName().toString()).reversed())
                    .map(this::toInfo)
                    .toList();
        } catch (IOException exception) {
            throw new UncheckedIOException("Falha ao listar backups.", exception);
        }
    }

    public void delete(String bundleName) {
        Path bundle = resolveBundle(bundleName);
        try (Stream<Path> walk = Files.walk(bundle)) {
            walk.sorted(Comparator.reverseOrder()).forEach(this::deleteQuietly);
        } catch (IOException exception) {
            throw new UncheckedIOException("Falha ao excluir o backup.", exception);
        }
        auditEventService.record("BACKUP_DELETE", "BACKUP", bundleName, null);
    }

    // ------------------------------------------------------------------ internos

    private BackupManifest buildManifest() {
        List<BackupManifest.BackupFileEntry> files = documentVersionRepository.findAll().stream()
                .map(version -> new BackupManifest.BackupFileEntry(version.getFilePath(), version.getSha256()))
                .toList();
        return BackupManifest.of(Instant.now(), userRepository.count(), officeRepository.count(),
                documentRepository.count(), files.size(), files);
    }

    private List<String> verifyHashes(Path bundle) {
        Path sumsFile = bundle.resolve("SHA256SUMS");
        if (!Files.isRegularFile(sumsFile)) {
            return List.of("SHA256SUMS ausente");
        }
        List<String> failures = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(sumsFile)) {
                if (line.isBlank()) {
                    continue;
                }
                String[] parts = line.split("\\s+", 2);
                if (parts.length != 2) {
                    failures.add("linha invalida: " + line);
                    continue;
                }
                Path artifact = bundle.resolve(parts[1]);
                if (!Files.isRegularFile(artifact)) {
                    failures.add("arquivo ausente: " + parts[1]);
                    continue;
                }
                String actual = Sha256Utils.sha256Hex(artifact);
                if (!actual.equalsIgnoreCase(parts[0])) {
                    failures.add("hash divergente: " + parts[1]);
                }
            }
        } catch (IOException exception) {
            throw new UncheckedIOException("Falha ao ler SHA256SUMS.", exception);
        }
        return failures;
    }

    private void writeChecksums(Path bundle, List<String> fileNames) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (String name : fileNames) {
            sb.append(Sha256Utils.sha256Hex(bundle.resolve(name))).append("  ").append(name).append('\n');
        }
        Files.writeString(bundle.resolve("SHA256SUMS"), sb.toString(), StandardCharsets.UTF_8);
    }

    /** Dropa e recria o banco de dados via banco de manutencao (sem depender do binario psql). */
    private void recreateDatabase() {
        String database = properties.getPgDatabase();
        String url = "jdbc:postgresql://%s:%d/%s".formatted(
                properties.getPgHost(), properties.getPgPort(), properties.getMaintenanceDb());
        try (Connection connection = DriverManager.getConnection(url, properties.getPgUser(), properties.getPgPassword())) {
            connection.setAutoCommit(true);
            terminateConnections(database, connection);
            try (Statement statement = connection.createStatement()) {
                statement.execute("DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
                statement.execute("CREATE DATABASE " + database);
            }
        } catch (SQLException exception) {
            throw new BusinessException("Falha ao recriar o banco de dados: " + exception.getMessage());
        }
    }

    private void terminateConnections(String database, Connection connection) throws SQLException {
        String sql = "SELECT pg_terminate_backend(pid) FROM pg_stat_activity "
                + "WHERE datname = ? AND pid <> pg_backend_pid()";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, database);
            statement.executeUpdate();
        }
    }

    private Path resolveBundle(String bundleName) {
        if (bundleName == null || bundleName.contains("..") || bundleName.contains("/")
                || !bundleName.startsWith("ged-")) {
            throw new BusinessException("Nome de backup invalido.");
        }
        Path bundle = Path.of(properties.getDir()).toAbsolutePath().normalize().resolve(bundleName);
        if (!Files.isDirectory(bundle)) {
            throw new BusinessException("Backup nao encontrado: " + bundleName);
        }
        return bundle;
    }

    private BackupInfo toInfo(Path bundle) {
        try {
            BackupManifest manifest = objectMapper.readValue(bundle.resolve("manifest.json").toFile(), BackupManifest.class);
            long size = Files.size(bundle.resolve("database.dump"));
            return new BackupInfo(bundle.getFileName().toString(), manifest.createdAt(), size,
                    manifest.versions(), manifest.documents());
        } catch (IOException exception) {
            return new BackupInfo(bundle.getFileName().toString(), null, -1L, -1L, -1L);
        }
    }

    private void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            log.warn("Nao foi possivel excluir {}.", file);
        }
    }

    private void deleteContents(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(directory)) {
            walk.sorted(Comparator.reverseOrder()).skip(1).forEach(this::deleteQuietly);
        }
    }

    private String[] pgEnv() {
        List<String> env = new ArrayList<>();
        env.add("PGHOST=" + properties.getPgHost());
        env.add("PGPORT=" + properties.getPgPort());
        env.add("PGUSER=" + properties.getPgUser());
        if (properties.getPgPassword() != null && !properties.getPgPassword().isBlank()) {
            env.add("PGPASSWORD=" + properties.getPgPassword());
        }
        return env.toArray(new String[0]);
    }

    private void validateDatabaseName(String database) {
        if (database == null || !database.matches("[a-zA-Z_][a-zA-Z0-9_]*")) {
            throw new BusinessException("Nome do banco de dados invalido para backup: " + database);
        }
    }

    private String pgBinary(String name) {
        String dir = properties.getPgBinDir();
        if (dir == null || dir.isBlank()) {
            return name;
        }
        return Path.of(dir, name).toString();
    }

    private void runCommand(List<String> command, String[] extraEnv) {
        ProcessBuilder builder = new ProcessBuilder(command);
        if (extraEnv != null) {
            for (String env : extraEnv) {
                int idx = env.indexOf('=');
                builder.environment().put(env.substring(0, idx), env.substring(idx + 1));
            }
        }
        builder.redirectErrorStream(true);
        try {
            Process process = builder.start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exit = process.waitFor();
            if (exit != 0) {
                String tail = output.isBlank() ? "sem saida" : output.lines().limit(5).reduce("", (a, b) -> a + b + " ");
                throw new BusinessException("Comando '%s' falhou (exit %d): %s".formatted(command.get(0), exit, tail));
            }
        } catch (IOException exception) {
            throw new UncheckedIOException("Falha ao executar %s.".formatted(command.get(0)), exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BusinessException("Comando interrompido: " + command.get(0));
        }
    }
}
