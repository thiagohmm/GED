package br.com.ged.backup;

import br.com.ged.audit.AuditEventService;
import br.com.ged.document.DocumentRepository;
import br.com.ged.document.DocumentVersionRepository;
import br.com.ged.document.StorageService;
import br.com.ged.exception.BusinessException;
import br.com.ged.exception.ConflictException;
import br.com.ged.integrity.IntegrityReport;
import br.com.ged.integrity.IntegrityService;
import br.com.ged.office.OfficeRepository;
import br.com.ged.search.ReindexService;
import br.com.ged.user.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
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
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

@Service
@Slf4j
public class BackupService {

    private static final DateTimeFormatter BUNDLE_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss").withZone(ZoneOffset.UTC);
    private static final Set<String> REQUIRED_ARTIFACTS =
            Set.of("database.dump", "storage.tar.gz", "manifest.json");
    private static final long COMMAND_TIMEOUT_SECONDS = 300;

    private final ReentrantLock operationLock = new ReentrantLock();
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
    private final DataSource dataSource;

    @Autowired
    public BackupService(BackupProperties properties,
                         StorageService storageService,
                         UserRepository userRepository,
                         OfficeRepository officeRepository,
                         DocumentRepository documentRepository,
                         DocumentVersionRepository documentVersionRepository,
                         AuditEventService auditEventService,
                         IntegrityService integrityService,
                         ReindexService reindexService,
                         ObjectMapper objectMapper,
                         @Autowired(required = false) DataSource dataSource) {
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
        this.dataSource = dataSource;
    }

    /** Cria um bundle de backup completo e retorna o nome da pasta gerada. */
    public String create() {
        if (!operationLock.tryLock()) {
            throw new ConflictException("Outra operacao de backup ou restore ja esta em andamento.");
        }
        try {
            validateDatabaseName(properties.getPgDatabase());
            Path backupRoot = Path.of(properties.getDir()).toAbsolutePath().normalize();
            Path bundle = backupRoot.resolve("ged-" + BUNDLE_FORMAT.format(Instant.now()));
            Files.createDirectories(bundle);

            Path dump = bundle.resolve("database.dump");
            runPgCommand(List.of(pgBinary("pg_dump"), "-Fc", "-f", dump.toString()));

            Path storageRoot = storageService.storageRoot();
            Files.createDirectories(storageRoot);
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
        } finally {
            operationLock.unlock();
        }
    }

    /** Restaura um bundle: valida hashes e tar antes de recriar o banco, restaura storage, verifica integridade e reindexa. */
    public void restore(String bundleName) {
        if (!operationLock.tryLock()) {
            throw new ConflictException("Outra operacao de backup ou restore ja esta em andamento.");
        }
        try {
            Path bundle = resolveBundle(bundleName);
            List<String> failures = verifyHashes(bundle);
            if (!failures.isEmpty()) {
                throw new BusinessException("Hashes do backup nao conferem: " + String.join("; ", failures));
            }
            validateDatabaseName(properties.getPgDatabase());
            validateDatabaseName(properties.getMaintenanceDb());

            Path storageRoot = storageService.storageRoot();
            Path storageArchive = bundle.resolve("storage.tar.gz");
            validateTarArchiveEntries(storageArchive, storageRoot.getFileName().toString());

            evictHikariConnections();
            recreateDatabase();
            try {
                runPgCommand(List.of(pgBinary("pg_restore"), "-d", properties.getPgDatabase(),
                        bundle.resolve("database.dump").toString()));
            } catch (BusinessException exception) {
                throw new BusinessException("Falha ao restaurar o banco de dados: " + exception.getMessage());
            } finally {
                evictHikariConnections();
            }

            extractStorageSafely(storageArchive, storageRoot);

            boolean reindexed = false;
            try {
                IntegrityReport integrity = integrityService.check();
                if (!integrity.allOk()) {
                    throw new BusinessException("Restore abortado: integridade da storage falhou em "
                            + integrity.issues().size() + " versao(es).");
                }
            } finally {
                try {
                    reindexed = reindexService.reindexAll();
                } catch (RuntimeException reindexError) {
                    log.warn("Reindexacao apos restore falhou: {}", reindexError.getMessage());
                }
            }

            log.info("Restore de {} concluido (reindexacao: {}).", bundleName, reindexed ? "ok" : "pulada");
            auditEventService.record("RESTORE", "BACKUP", bundleName,
                    AuditEventService.details("reindex", reindexed));
        } finally {
            operationLock.unlock();
        }
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
        if (!operationLock.tryLock()) {
            throw new ConflictException("Outra operacao de backup ou restore ja esta em andamento.");
        }
        try {
            Path bundle = resolveBundle(bundleName);
            try (Stream<Path> walk = Files.walk(bundle)) {
                walk.sorted(Comparator.reverseOrder()).forEach(this::deleteQuietly);
            } catch (IOException exception) {
                throw new UncheckedIOException("Falha ao excluir o backup.", exception);
            }
            auditEventService.record("BACKUP_DELETE", "BACKUP", bundleName, null);
        } finally {
            operationLock.unlock();
        }
    }

    private BackupManifest buildManifest() {
        List<BackupManifest.BackupFileEntry> files = documentVersionRepository.findAll().stream()
                .map(version -> new BackupManifest.BackupFileEntry(version.getFilePath(), version.getSha256()))
                .toList();
        return BackupManifest.of(Instant.now(), userRepository.count(), officeRepository.count(),
                documentRepository.count(), files.size(), files);
    }

    List<String> verifyHashes(Path bundle) {
        Path normalizedBundle = bundle.toAbsolutePath().normalize();
        Path sumsFile = normalizedBundle.resolve("SHA256SUMS");
        if (!Files.isRegularFile(sumsFile)) {
            return List.of("SHA256SUMS ausente");
        }
        List<String> failures = new ArrayList<>();
        Set<String> verifiedNames = new HashSet<>();
        try {
            for (String line : Files.readAllLines(sumsFile)) {
                if (line.isBlank()) {
                    continue;
                }
                String[] parts = line.trim().split("\\s+", 2);
                if (parts.length != 2) {
                    failures.add("linha invalida: " + line);
                    continue;
                }
                String fileName = parts[1].trim();
                if (fileName.contains("/") || fileName.contains("\\") || fileName.contains("..")
                        || !REQUIRED_ARTIFACTS.contains(fileName)) {
                    failures.add("artefato nao permitido em SHA256SUMS: " + fileName);
                    continue;
                }
                Path artifact = normalizedBundle.resolve(fileName).normalize();
                if (!artifact.getParent().equals(normalizedBundle) || Files.isSymbolicLink(artifact)) {
                    failures.add("caminho invalido: " + fileName);
                    continue;
                }
                if (!Files.isRegularFile(artifact)) {
                    failures.add("arquivo ausente: " + fileName);
                    continue;
                }
                String actual = Sha256Utils.sha256Hex(artifact);
                if (!actual.equalsIgnoreCase(parts[0])) {
                    failures.add("hash divergente: " + fileName);
                } else {
                    verifiedNames.add(fileName);
                }
            }
            for (String required : REQUIRED_ARTIFACTS) {
                if (!verifiedNames.contains(required) && failures.stream().noneMatch(f -> f.endsWith(required))) {
                    failures.add("artefato obrigatorio ausente em SHA256SUMS: " + required);
                }
            }
        } catch (IOException exception) {
            throw new UncheckedIOException("Falha ao ler SHA256SUMS.", exception);
        }
        return failures;
    }

    void validateTarArchiveEntries(Path storageArchive, String expectedRootFolderName) {
        String listing = runCommand(List.of("tar", "-tvzf", storageArchive.toString()), null);
        for (String line : listing.lines().toList()) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            char entryType = trimmed.charAt(0);
            if (entryType != '-' && entryType != 'd') {
                throw new BusinessException("Arquivo de storage contem entrada nao permitida (symlink/especial): " + trimmed);
            }
        }
        String names = runCommand(List.of("tar", "-tzf", storageArchive.toString()), null);
        String requiredPrefix = expectedRootFolderName + "/";
        for (String rawEntry : names.lines().toList()) {
            String entry = rawEntry.trim();
            if (entry.isEmpty()) {
                continue;
            }
            if (entry.startsWith("/") || entry.startsWith("\\") || entry.contains("\\")) {
                throw new BusinessException("Entrada insegura no arquivo de storage: " + entry);
            }
            Path rel = Path.of(entry).normalize();
            if (rel.isAbsolute() || entry.contains("..") || rel.startsWith("..")) {
                throw new BusinessException("Tentativa de path traversal detectada no arquivo de storage: " + entry);
            }
            if (!entry.equals(expectedRootFolderName) && !entry.startsWith(requiredPrefix)) {
                throw new BusinessException("Entrada fora da raiz esperada na storage: " + entry);
            }
        }
    }

    private void extractStorageSafely(Path storageArchive, Path storageRoot) {
        Path stagingDir = null;
        try {
            stagingDir = Files.createTempDirectory("ged-restore-staging-");
            runCommand(List.of("tar", "-xzf", storageArchive.toString(), "-C", stagingDir.toString()), null);

            Path extractedRoot = stagingDir.resolve(storageRoot.getFileName().toString()).normalize();
            if (!extractedRoot.startsWith(stagingDir) || !Files.isDirectory(extractedRoot)) {
                throw new BusinessException("Estrutura do backup de storage invalida.");
            }
            try (Stream<Path> checkWalk = Files.walk(extractedRoot)) {
                if (checkWalk.anyMatch(Files::isSymbolicLink)) {
                    throw new BusinessException("Links simbolicos nao sao permitidos no backup de storage.");
                }
            }

            deleteContents(storageRoot);
            Files.createDirectories(storageRoot);
            try (Stream<Path> walk = Files.walk(extractedRoot)) {
                for (Path source : walk.toList()) {
                    Path relative = extractedRoot.relativize(source);
                    Path target = storageRoot.resolve(relative).normalize();
                    if (!target.startsWith(storageRoot)) {
                        throw new BusinessException("Destino invalido durante restauracao da storage.");
                    }
                    if (Files.isDirectory(source)) {
                        Files.createDirectories(target);
                    } else if (Files.isRegularFile(source)) {
                        Files.createDirectories(target.getParent());
                        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        } catch (IOException exception) {
            throw new UncheckedIOException("Falha ao restaurar a storage de arquivos.", exception);
        } finally {
            if (stagingDir != null) {
                try (Stream<Path> cleanup = Files.walk(stagingDir)) {
                    cleanup.sorted(Comparator.reverseOrder()).forEach(this::deleteQuietly);
                } catch (IOException ignored) {
                }
            }
        }
    }

    private void evictHikariConnections() {
        if (dataSource instanceof HikariDataSource hikari && hikari.getHikariPoolMXBean() != null) {
            try {
                hikari.getHikariPoolMXBean().softEvictConnections();
            } catch (RuntimeException exception) {
                log.debug("Nao foi possivel reciclar conexoes Hikari: {}", exception.getMessage());
            }
        }
    }

    private void writeChecksums(Path bundle, List<String> fileNames) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (String name : fileNames) {
            sb.append(Sha256Utils.sha256Hex(bundle.resolve(name))).append("  ").append(name).append('\n');
        }
        Files.writeString(bundle.resolve("SHA256SUMS"), sb.toString(), StandardCharsets.UTF_8);
    }

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
            statement.execute();
        }
    }

    private Path resolveBundle(String bundleName) {
        if (bundleName == null || bundleName.contains("..") || bundleName.contains("/")
                || bundleName.contains("\\") || !bundleName.startsWith("ged-")) {
            throw new BusinessException("Nome de backup invalido.");
        }
        Path backupRoot = Path.of(properties.getDir()).toAbsolutePath().normalize();
        Path bundle = backupRoot.resolve(bundleName).normalize();
        if (!bundle.getParent().equals(backupRoot) || !Files.isDirectory(bundle) || Files.isSymbolicLink(bundle)) {
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

    private void runPgCommand(List<String> command) {
        Path pgPassFile = null;
        try {
            List<String> env = new ArrayList<>();
            env.add("PGHOST=" + properties.getPgHost());
            env.add("PGPORT=" + properties.getPgPort());
            env.add("PGUSER=" + properties.getPgUser());
            if (properties.getPgPassword() != null && !properties.getPgPassword().isBlank()) {
                if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
                    pgPassFile = Files.createTempFile("ged-pgpass-", ".conf",
                            PosixFilePermissions.asFileAttribute(EnumSet.of(
                                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)));
                    String line = "%s:%d:*:%s:%s\n".formatted(
                            escapePgPass(properties.getPgHost()),
                            properties.getPgPort(),
                            escapePgPass(properties.getPgUser()),
                            escapePgPass(properties.getPgPassword()));
                    Files.writeString(pgPassFile, line, StandardCharsets.UTF_8);
                    env.add("PGPASSFILE=" + pgPassFile.toAbsolutePath());
                } else {
                    env.add("PGPASSWORD=" + properties.getPgPassword());
                }
            }
            runCommand(command, env.toArray(new String[0]));
        } catch (IOException exception) {
            throw new UncheckedIOException("Falha ao preparar credenciais temporarias do PostgreSQL.", exception);
        } finally {
            if (pgPassFile != null) {
                deleteQuietly(pgPassFile);
            }
        }
    }

    private static String escapePgPass(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace(":", "\\:");
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

    private String runCommand(List<String> command, String[] extraEnv) {
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
            CompletableFuture<String> stdoutFuture = CompletableFuture.supplyAsync(() -> {
                try {
                    return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                } catch (IOException exception) {
                    throw new UncheckedIOException(exception);
                }
            });
            boolean finished = process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new BusinessException("Comando '%s' excedeu o tempo limite.".formatted(command.get(0)));
            }
            String output = stdoutFuture.join();
            int exit = process.exitValue();
            if (exit != 0) {
                String tail = output.isBlank() ? "sem saida" : output.lines().limit(5).reduce("", (a, b) -> a + b + " ");
                throw new BusinessException("Comando '%s' falhou (exit %d): %s".formatted(command.get(0), exit, tail));
            }
            return output;
        } catch (IOException exception) {
            throw new UncheckedIOException("Falha ao executar %s.".formatted(command.get(0)), exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BusinessException("Comando interrompido: " + command.get(0));
        }
    }
}
