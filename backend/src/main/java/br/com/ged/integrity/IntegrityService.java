package br.com.ged.integrity;

import br.com.ged.document.Document;
import br.com.ged.document.DocumentRepository;
import br.com.ged.document.DocumentVersion;
import br.com.ged.document.StorageService;
import br.com.ged.audit.AuditEventService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Verifica a integridade dos arquivos binarios na storage:
 *  1. o arquivo do versionamento existe em disco;
 *  2. o SHA-256 do arquivo bate com o hash gravado no banco.
 */
@Service
public class IntegrityService {

    private final DocumentRepository documentRepository;
    private final StorageService storageService;
    private final AuditEventService auditEventService;

    public IntegrityService(DocumentRepository documentRepository,
                            StorageService storageService,
                            AuditEventService auditEventService) {
        this.documentRepository = documentRepository;
        this.storageService = storageService;
        this.auditEventService = auditEventService;
    }

    @Transactional(readOnly = true)
    public IntegrityReport check() {
        List<IntegrityIssue> issues = new ArrayList<>();
        long total = 0;
        long ok = 0;
        for (Document document : documentRepository.findAll()) {
            for (DocumentVersion version : document.getVersions()) {
                total++;
                IntegrityIssue issue = verifyVersion(document, version);
                if (issue == null) {
                    ok++;
                } else {
                    issues.add(issue);
                }
            }
        }
        auditEventService.record("INTEGRITY_CHECK", "STORAGE", null,
                AuditEventService.details("total", total, "ok", ok, "issues", issues.size()));
        return new IntegrityReport(total, ok, issues);
    }

    private IntegrityIssue verifyVersion(Document document, DocumentVersion version) {
        Path file = storageService.resolveStored(version.getFilePath());
        if (!Files.isRegularFile(file)) {
            return new IntegrityIssue(document.getId(), document.getName(), version.getVersionNumber(),
                    "ARQUIVO_INEXISTENTE", version.getFilePath(), version.getSha256(), null);
        }
        String actualHash = sha256(file);
        String expected = version.getSha256();
        if (expected != null && !expected.isBlank() && !expected.equalsIgnoreCase(actualHash)) {
            return new IntegrityIssue(document.getId(), document.getName(), version.getVersionNumber(),
                    "HASH_DIFERENTE", version.getFilePath(), expected, actualHash);
        }
        return null;
    }

    private String sha256(Path file) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 indisponivel.", exception);
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Falha ao ler o arquivo da storage.", exception);
        }
    }
}
