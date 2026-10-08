package br.com.ged.document;

import br.com.ged.config.JwtService;
import br.com.ged.exception.BusinessException;
import br.com.ged.exception.ConflictException;
import br.com.ged.exception.NotFoundException;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class DocumentEditorService {

    private final DocumentRepository documentRepository;
    private final DocumentVersionRepository versionRepository;
    private final DocumentService documentService;
    private final JwtService jwtService;
    @Value("${ged.editor.document-server-url}")
    private String documentServerUrl;
    @Value("${ged.editor.public-backend-url}")
    private String publicBackendUrl;

    @Transactional(readOnly = true)
    public EditorConfigResponse config(Long documentId, String username, String mode) {
        boolean edit = "edit".equals(mode);
        if (!edit && !"view".equals(mode)) {
            throw new BusinessException("Modo de editor invalido.");
        }
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new NotFoundException("Documento nao encontrado."));
        if (document.getKind() != DocumentKind.WORD && document.getKind() != DocumentKind.EXCEL
                && document.getKind() != DocumentKind.PDF) {
            throw new BusinessException("O editor integrado aceita Word, Excel e PDF.");
        }
        if (edit && document.getStatus() != DocumentStatus.EDITANDO) {
            throw new ConflictException("O documento precisa estar em edicao para abrir o editor.");
        }
        DocumentVersion version = versionRepository.findTopByDocumentOrderByVersionNumberDesc(document)
                .orElseThrow(() -> new NotFoundException("Versao do documento nao encontrada."));
        String extension = document.getName().substring(document.getName().lastIndexOf('.') + 1).toLowerCase();
        String key = document.getId() + "-v" + version.getVersionNumber();
        Instant expires = Instant.now().plusSeconds(12 * 60 * 60);
        Map<String, Object> fileClaims = new LinkedHashMap<>();
        fileClaims.put("versionId", version.getId());
        fileClaims.put("documentId", documentId);
        fileClaims.put("sub", username);
        fileClaims.put("exp", Date.from(expires));
        String fileToken = jwtService.sign(fileClaims);
        String fileUrl = publicBackendUrl + "/api/editor/versions/" + version.getId() + "/file?token=" + fileToken;

        Map<String, Object> editorUser = Map.of("id", username, "name", username);
        Map<String, Object> editorConfig = new LinkedHashMap<>();
        editorConfig.put("mode", edit ? "edit" : "view");
        if (edit) {
            editorConfig.put("callbackUrl", publicBackendUrl + "/api/editor/callback/" + documentId
                    + "?token=" + sessionToken(document, version, username, key, expires));
        }
        editorConfig.put("user", editorUser);

        String documentType = switch (document.getKind()) {
            case WORD -> "word";
            case EXCEL -> "cell";
            default -> "pdf";
        };

        Map<String, Object> documentConfig = new LinkedHashMap<>();
        documentConfig.put("fileType", extension);
        documentConfig.put("key", key);
        documentConfig.put("title", document.getName());
        documentConfig.put("url", fileUrl);
        documentConfig.put("permissions", edit
                ? Map.of("edit", true, "download", true, "print", true)
                : Map.of("edit", false, "download", true, "print", true));

        Map<String, Object> config = new LinkedHashMap<>();
        config.put("documentType", documentType);
        config.put("document", documentConfig);
        config.put("editorConfig", editorConfig);
        config.put("height", "100%");
        config.put("width", "100%");
        config.put("token", jwtService.sign(config));
        return new EditorConfigResponse(documentServerUrl, config);
    }

    @Transactional(readOnly = true)
    public VersionDownload editorFile(Long versionId, String token) {
        Claims claims = verifyToken(token);
        long tokenVersionId = numberClaim(claims, "versionId");
        if (tokenVersionId != versionId) throw new BusinessException("Token de arquivo invalido.");
        return documentService.downloadVersion(versionId);
    }

    public Map<String, Integer> callback(Long documentId, String session, Map<String, Object> body) {
        Claims sessionClaims = verifyToken(session);
        if (numberClaim(sessionClaims, "documentId") != documentId) {
            throw new BusinessException("Sessao de edicao invalida.");
        }
        Object bodyToken = body.get("token");
        if (!(bodyToken instanceof String signedCallback)) {
            throw new BusinessException("Callback do editor sem assinatura.");
        }
        Claims callbackClaims = verifyToken(signedCallback);
        String key = String.valueOf(sessionClaims.get("key"));
        if (!key.equals(callbackClaims.get("key"))) throw new BusinessException("Callback do editor invalido.");
        int status = ((Number) callbackClaims.get("status")).intValue();
        if (status != 2) return Map.of("error", 0);
        String fileUrl = String.valueOf(callbackClaims.get("url"));
        byte[] content = fetchEditedFile(fileUrl);
        if (content.length > 50L * 1024 * 1024) throw new BusinessException("Arquivo editado maior que 50MB.");
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new NotFoundException("Documento nao encontrado."));
        int expectedVersion = ((Number) sessionClaims.get("versionNumber")).intValue();
        if (document.getCurrentVersion() > expectedVersion) return Map.of("error", 0);
        if (document.getCurrentVersion() != expectedVersion) throw new ConflictException("A versao foi alterada durante a edicao.");
        documentService.saveEditedVersion(documentId, content, String.valueOf(sessionClaims.get("sub")));
        return Map.of("error", 0);
    }

    private String sessionToken(Document document, DocumentVersion version, String username, String key, Instant expires) {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("documentId", document.getId());
        claims.put("versionId", version.getId());
        claims.put("versionNumber", version.getVersionNumber());
        claims.put("sub", username);
        claims.put("key", key);
        claims.put("exp", Date.from(expires));
        return jwtService.sign(claims);
    }

    private Claims verifyToken(String token) {
        try {
            return jwtService.verify(token);
        } catch (RuntimeException exception) {
            throw new BusinessException("Token de editor invalido ou expirado.");
        }
    }

    private long numberClaim(Claims claims, String name) {
        Object value = claims.get(name);
        if (!(value instanceof Number number)) throw new BusinessException("Token de editor invalido.");
        return number.longValue();
    }

    private byte[] fetchEditedFile(String fileUrl) {
        URI uri = URI.create(fileUrl);
        URI allowed = URI.create(documentServerUrl);
        if (!allowed.getHost().equalsIgnoreCase(uri.getHost()) || !"https".equalsIgnoreCase(uri.getScheme())
                && !"http".equalsIgnoreCase(uri.getScheme())) {
            throw new BusinessException("Origem do arquivo editado invalida.");
        }
        byte[] content = RestClient.create().get().uri(uri).retrieve().body(byte[].class);
        if (content == null) throw new BusinessException("O editor nao retornou o arquivo atualizado.");
        return content;
    }
}
