package br.com.ged.document;

import br.com.ged.config.AuthenticatedUser;
import br.com.ged.config.JwtService;
import br.com.ged.exception.BusinessException;
import br.com.ged.exception.ConflictException;
import br.com.ged.exception.NotFoundException;
import br.com.ged.tenant.TenantScope;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class DocumentEditorService {

    private static final long MAX_EDITED_FILE_BYTES = 50L * 1024 * 1024;
    private static final String EDITOR_USER_CLAIM = "editorUser";

    private final DocumentRepository documentRepository;
    private final DocumentVersionRepository versionRepository;
    private final DocumentService documentService;
    private final JwtService jwtService;
    private final TenantScope tenantScope;

    @Value("${ged.editor.document-server-url}")
    private String documentServerUrl;

    @Value("${ged.editor.public-backend-url}")
    private String publicBackendUrl;

    @Value("${ged.editor.token-ttl-minutes:120}")
    private long tokenTtlMinutes = 120;

    @Transactional(readOnly = true)
    public EditorConfigResponse config(Long documentId, AuthenticatedUser actor, String mode) {
        String username = actor.username();
        boolean edit = "edit".equals(mode);
        if (!edit && !"view".equals(mode)) {
            throw new BusinessException("Modo de editor invalido.");
        }
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new NotFoundException("Documento nao encontrado."));
        tenantScope.requireAccessToDocument(document);
        if (document.getKind() != DocumentKind.WORD && document.getKind() != DocumentKind.EXCEL
                && document.getKind() != DocumentKind.PDF) {
            throw new BusinessException("O editor integrado aceita Word, Excel e PDF.");
        }
        if (edit && document.getStatus() != DocumentStatus.EDITANDO) {
            throw new ConflictException("O documento precisa estar em edicao para abrir o editor.");
        }
        DocumentVersion version = versionRepository.findTopByDocumentOrderByVersionNumberDesc(document)
                .orElseThrow(() -> new NotFoundException("Versao do documento nao encontrada."));

        Long resourceOfficeId = tenantScope.officeOfDocument(document).orElse(actor.officeId());
        String extension = document.getName().substring(document.getName().lastIndexOf('.') + 1).toLowerCase();
        String key = document.getId() + "-v" + version.getVersionNumber();
        Instant expires = Instant.now().plus(Math.max(tokenTtlMinutes, 5), ChronoUnit.MINUTES);

        Map<String, Object> fileClaims = new LinkedHashMap<>();
        fileClaims.put(JwtService.TYPE_CLAIM, JwtService.TYPE_EDITOR_FILE);
        fileClaims.put("versionId", version.getId());
        fileClaims.put("documentId", documentId);
        fileClaims.put("officeId", resourceOfficeId);
        fileClaims.put(EDITOR_USER_CLAIM, username);
        fileClaims.put("exp", Date.from(expires));
        String fileToken = jwtService.signEditor(fileClaims, expires);
        String fileUrl = publicBackendUrl + "/api/editor/versions/" + version.getId() + "/file?token=" + fileToken;

        Map<String, Object> editorUser = Map.of("id", username, "name", username);
        Map<String, Object> editorConfig = new LinkedHashMap<>();
        editorConfig.put("mode", edit ? "edit" : "view");
        if (edit) {
            editorConfig.put("callbackUrl", publicBackendUrl + "/api/editor/callback/" + documentId
                    + "?token=" + sessionToken(document, version, username, resourceOfficeId, key, expires));
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

        Map<String, Object> signedPayload = new LinkedHashMap<>(config);
        signedPayload.put(JwtService.TYPE_CLAIM, JwtService.TYPE_EDITOR_CONFIG);
        signedPayload.put("exp", Date.from(expires));
        config.put("token", jwtService.signEditor(signedPayload, expires));
        return new EditorConfigResponse(documentServerUrl, config);
    }

    @Transactional(readOnly = true)
    public VersionDownload editorFile(Long versionId, String token) {
        Claims claims = verifyEditorToken(token, JwtService.TYPE_EDITOR_FILE);
        long tokenVersionId = numberClaim(claims, "versionId");
        if (tokenVersionId != versionId) {
            throw new BusinessException("Token de arquivo invalido.");
        }
        DocumentVersion version = versionRepository.findById(versionId)
                .orElseThrow(() -> new NotFoundException("Versao nao encontrada."));
        Long tokenOffice = claims.get("officeId", Long.class);
        tenantScope.requireOfficeMatch(tokenOffice, tenantScope.officeOfDocument(version.getDocument()).orElse(null));
        return documentService.downloadVersionForEditor(version);
    }

    public Map<String, Integer> callback(Long documentId, String session, Map<String, Object> body) {
        Claims sessionClaims = verifyEditorToken(session, JwtService.TYPE_EDITOR_SESSION);
        if (numberClaim(sessionClaims, "documentId") != documentId) {
            throw new BusinessException("Sessao de edicao invalida.");
        }
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new NotFoundException("Documento nao encontrado."));
        Long sessionOffice = sessionClaims.get("officeId", Long.class);
        tenantScope.requireOfficeMatch(sessionOffice, tenantScope.officeOfDocument(document).orElse(null));
        Object bodyToken = body == null ? null : body.get("token");
        if (!(bodyToken instanceof String signedCallback)) {
            throw new BusinessException("Callback do editor sem assinatura.");
        }
        Claims callbackClaims = verifyUntypedOnlyOfficeCallback(signedCallback);
        String key = String.valueOf(sessionClaims.get("key"));
        if (!key.equals(callbackClaims.get("key"))) {
            throw new BusinessException("Callback do editor invalido.");
        }
        Object statusValue = callbackClaims.get("status");
        if (!(statusValue instanceof Number statusNumber)) {
            throw new BusinessException("Status de callback do editor invalido.");
        }
        int status = statusNumber.intValue();
        if (status != 2) {
            return Map.of("error", 0);
        }
        if (document.getStatus() != DocumentStatus.EDITANDO) {
            throw new ConflictException("O documento nao esta mais em edicao e nao aceita salvamento pelo editor.");
        }
        int expectedVersion = (int) numberClaim(sessionClaims, "versionNumber");
        if (document.getCurrentVersion() != expectedVersion) {
            log.warn("Callback OnlyOffice rejeitado para documento {}: versao esperada {}, atual {}.",
                    documentId, expectedVersion, document.getCurrentVersion());
            throw new ConflictException("A versao foi alterada durante a edicao.");
        }
        Object rawUrl = callbackClaims.get("url");
        if (!(rawUrl instanceof String fileUrl) || fileUrl.isBlank()) {
            throw new BusinessException("URL do arquivo editado ausente no callback.");
        }
        byte[] content = fetchEditedFile(fileUrl);
        String editorUsername = sessionClaims.get(EDITOR_USER_CLAIM, String.class);
        if (editorUsername == null || editorUsername.isBlank()) {
            throw new BusinessException("Usuario da sessao de edicao invalido.");
        }
        documentService.saveEditedVersion(documentId, content, editorUsername);
        return Map.of("error", 0);
    }

    private String sessionToken(Document document, DocumentVersion version, String username,
                                Long resourceOfficeId, String key, Instant expires) {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put(JwtService.TYPE_CLAIM, JwtService.TYPE_EDITOR_SESSION);
        claims.put("documentId", document.getId());
        claims.put("versionId", version.getId());
        claims.put("versionNumber", version.getVersionNumber());
        claims.put("officeId", resourceOfficeId);
        claims.put(EDITOR_USER_CLAIM, username);
        claims.put("key", key);
        claims.put("exp", Date.from(expires));
        return jwtService.signEditor(claims, expires);
    }

    private Claims verifyEditorToken(String token, String expectedType) {
        try {
            return jwtService.verifyEditor(token, expectedType);
        } catch (RuntimeException exception) {
            throw new BusinessException("Token de editor invalido ou expirado.");
        }
    }

    private Claims verifyUntypedOnlyOfficeCallback(String token) {
        try {
            Claims claims = jwtService.verifyEditor(token);
            if (claims.get(JwtService.TYPE_CLAIM) != null) {
                throw new BusinessException("Token de callback invalido.");
            }
            return claims;
        } catch (BusinessException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new BusinessException("Token de editor invalido ou expirado.");
        }
    }

    private long numberClaim(Claims claims, String name) {
        Object value = claims.get(name);
        if (!(value instanceof Number number)) {
            throw new BusinessException("Token de editor invalido.");
        }
        return number.longValue();
    }

    byte[] fetchEditedFile(String fileUrl) {
        URI uri = parseSafeUri(fileUrl);
        URI allowed = parseSafeUri(documentServerUrl);
        validateSameOrigin(uri, allowed);

        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(15));

        byte[] content = RestClient.builder()
                .requestFactory(requestFactory)
                .build()
                .get()
                .uri(uri)
                .exchange((request, response) -> {
                    if (!response.getStatusCode().is2xxSuccessful()) {
                        throw new BusinessException("Falha ao baixar arquivo editado do servidor de documentos.");
                    }
                    return readBoundedBytes(response.getBody(), MAX_EDITED_FILE_BYTES);
                });
        if (content == null || content.length == 0) {
            throw new BusinessException("O editor nao retornou o arquivo atualizado.");
        }
        return content;
    }

    static void validateSameOrigin(URI uri, URI allowed) {
        String scheme = uri.getScheme();
        String allowedScheme = allowed.getScheme();
        if (scheme == null || allowedScheme == null
                || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))
                || !allowedScheme.equalsIgnoreCase(scheme)) {
            throw new BusinessException("Origem do arquivo editado invalida (esquema).");
        }
        if (uri.getUserInfo() != null) {
            throw new BusinessException("Origem do arquivo editado invalida (credenciais na URL).");
        }
        if (uri.getHost() == null || allowed.getHost() == null
                || !allowed.getHost().equalsIgnoreCase(uri.getHost())) {
            throw new BusinessException("Origem do arquivo editado invalida (host).");
        }
        if (effectivePort(uri) != effectivePort(allowed)) {
            throw new BusinessException("Origem do arquivo editado invalida (porta).");
        }
    }

    private static URI parseSafeUri(String raw) {
        try {
            return URI.create(raw);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException("URL de arquivo editado malformada.");
        }
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() != -1) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private static byte[] readBoundedBytes(InputStream body, long maxBytes) throws IOException {
        if (body == null) {
            return new byte[0];
        }
        try (InputStream in = body; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            long total = 0;
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > maxBytes) {
                    throw new BusinessException("Arquivo editado maior que 50MB.");
                }
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }
}
