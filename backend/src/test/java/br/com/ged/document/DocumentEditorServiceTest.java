package br.com.ged.document;

import br.com.ged.config.JwtService;
import br.com.ged.exception.BusinessException;
import br.com.ged.exception.ConflictException;
import br.com.ged.exception.NotFoundException;
import br.com.ged.folder.Folder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DocumentEditorServiceTest {

    private static final long DOCUMENTO_ID = 10L;
    private static final String SESSION_SECRET = "segredo-de-teste-com-no-minimo-32-caracteres!!";
    private static final String EDITOR_SECRET = "segredo-exclusivo-do-editor-com-32-caracteres!";

    @Mock
    private DocumentRepository documentRepository;

    @Mock
    private DocumentVersionRepository versionRepository;

    @Mock
    private DocumentService documentService;

    @Mock
    private br.com.ged.tenant.TenantScope tenantScope;

    private JwtService jwtService;
    private DocumentEditorService editorService;
    private Folder folder;

    @BeforeEach
    void setUp() {
        jwtService = new JwtService(SESSION_SECRET, EDITOR_SECRET, 720);
        editorService = new DocumentEditorService(
                documentRepository,
                versionRepository,
                documentService,
                jwtService,
                tenantScope);
        ReflectionTestUtils.setField(editorService, "documentServerUrl", "http://localhost:8090");
        ReflectionTestUtils.setField(editorService, "publicBackendUrl", "http://host.docker.internal:8080");
        folder = new Folder("Contratos", null, "admin");
        folder.setId(1L);
    }

    @Test
    void visualizacaoDeExcelEmQualquerStatusAbrePlanilhaSemPermissaoDeEdicao() {
        Document documento = documento("planilha.xlsx", DocumentKind.EXCEL, DocumentStatus.EM_REVISAO);
        versionar(documento, 2);
        when(documentRepository.findById(DOCUMENTO_ID)).thenReturn(Optional.of(documento));

        EditorConfigResponse response = editorService.config(DOCUMENTO_ID, ator("revisor", br.com.ged.user.Role.REVISOR), "view");

        assertEquals("http://localhost:8090", response.documentServerUrl());
        Map<String, Object> config = response.config();
        assertEquals("cell", config.get("documentType"));
        Map<String, Object> documentConfig = documentoConfig(config);
        assertEquals("xlsx", documentConfig.get("fileType"));
        assertEquals(false, permissions(config).get("edit"));
        Map<String, Object> editorConfig = editorConfig(config);
        assertEquals("view", editorConfig.get("mode"));
        assertFalse(editorConfig.containsKey("callbackUrl"));
        assertTrue(config.containsKey("token"), "Config precisa estar assinado com JWT");
        assertTrue(jwtService.verifyEditor(String.valueOf(config.get("token")), JwtService.TYPE_EDITOR_CONFIG).getExpiration() != null);
    }

    @Test
    void edicaoDeExcelEmEditandoRetornaCallbackEPermissaoDeEdicao() {
        Document documento = documento("planilha.xlsx", DocumentKind.EXCEL, DocumentStatus.EDITANDO);
        versionar(documento, 1);
        when(documentRepository.findById(DOCUMENTO_ID)).thenReturn(Optional.of(documento));

        EditorConfigResponse response = editorService.config(DOCUMENTO_ID, ator("admin", br.com.ged.user.Role.ADMIN), "edit");

        assertEquals("cell", response.config().get("documentType"));
        assertEquals(true, permissions(response.config()).get("edit"));
        Map<String, Object> editorConfig = editorConfig(response.config());
        assertEquals("edit", editorConfig.get("mode"));
        assertTrue(String.valueOf(editorConfig.get("callbackUrl"))
                .startsWith("http://host.docker.internal:8080/api/editor/callback/"));
    }

    @Test
    void callbackRejeitaSalvamentoSeDocumentoSaiuDeEditando() {
        Document documento = documento("peticao.docx", DocumentKind.WORD, DocumentStatus.EDITANDO);
        versionar(documento, 1);
        when(documentRepository.findById(DOCUMENTO_ID)).thenReturn(Optional.of(documento));
        when(tenantScope.officeOfDocument(documento)).thenReturn(Optional.of(7L));

        EditorConfigResponse config = editorService.config(DOCUMENTO_ID, ator("admin", br.com.ged.user.Role.ADMIN), "edit");
        String callbackUrl = String.valueOf(editorConfig(config.config()).get("callbackUrl"));
        String sessionToken = callbackUrl.substring(callbackUrl.indexOf("?token=") + 7);

        documento.setStatus(DocumentStatus.EM_REVISAO);
        String signedCallback = jwtService.signEditor(
                Map.of("key", DOCUMENTO_ID + "-v1", "status", 2, "url", "http://localhost:8090/cache/out.docx"),
                Instant.now().plusSeconds(300));

        assertThrows(ConflictException.class,
                () -> editorService.callback(DOCUMENTO_ID, sessionToken, Map.of("token", signedCallback)));
    }

    @Test
    void validacaoSsrfExigeMesmoEsquemaHostEPortaDoDocumentServer() {
        URI allowed = URI.create("http://localhost:8090");
        assertDoesNotThrow(() -> DocumentEditorService.validateSameOrigin(
                URI.create("http://localhost:8090/cache/files/doc.docx"), allowed));
        assertThrows(BusinessException.class, () -> DocumentEditorService.validateSameOrigin(
                URI.create("http://localhost:6379/info"), allowed));
        assertThrows(BusinessException.class, () -> DocumentEditorService.validateSameOrigin(
                URI.create("https://localhost:8090/cache/files/doc.docx"), allowed));
        assertThrows(BusinessException.class, () -> DocumentEditorService.validateSameOrigin(
                URI.create("http://169.254.169.254:8090/latest/meta-data"), allowed));
        assertThrows(BusinessException.class, () -> DocumentEditorService.validateSameOrigin(
                URI.create("http://evil@localhost:8090/cache/files/doc.docx"), allowed));
    }

    @Test
    void edicaoForaDeEditandoEhBloqueada() {
        Document documento = documento("peticao.docx", DocumentKind.WORD, DocumentStatus.EM_REVISAO);
        versionar(documento, 1);
        when(documentRepository.findById(DOCUMENTO_ID)).thenReturn(Optional.of(documento));

        assertThrows(ConflictException.class, () -> editorService.config(DOCUMENTO_ID, ator("admin", br.com.ged.user.Role.ADMIN), "edit"));
    }

    @Test
    void visualizacaoFuncionaMesmoProtocolado() {
        Document documento = documento("acordao.pdf", DocumentKind.PDF, DocumentStatus.PROTOCOLADO);
        versionar(documento, 3);
        when(documentRepository.findById(DOCUMENTO_ID)).thenReturn(Optional.of(documento));

        EditorConfigResponse response = editorService.config(DOCUMENTO_ID, ator("admin", br.com.ged.user.Role.ADMIN), "view");

        assertEquals("pdf", response.config().get("documentType"));
        assertEquals("view", editorConfig(response.config()).get("mode"));
    }

    @Test
    void edicaoDePdfEmEditandoAbreEditorPdf() {
        Document documento = documento("acordao.pdf", DocumentKind.PDF, DocumentStatus.EDITANDO);
        versionar(documento, 1);
        when(documentRepository.findById(DOCUMENTO_ID)).thenReturn(Optional.of(documento));

        EditorConfigResponse response = editorService.config(DOCUMENTO_ID, ator("admin", br.com.ged.user.Role.ADMIN), "edit");

        assertEquals("pdf", response.config().get("documentType"));
        assertEquals("edit", editorConfig(response.config()).get("mode"));
    }

    @Test
    void imagemNaoTemEditorIntegrado() {
        Document documento = documento("foto.png", DocumentKind.IMAGE, DocumentStatus.EDITANDO);
        when(documentRepository.findById(DOCUMENTO_ID)).thenReturn(Optional.of(documento));

        assertThrows(BusinessException.class, () -> editorService.config(DOCUMENTO_ID, ator("admin", br.com.ged.user.Role.ADMIN), "view"));
    }

    @Test
    void modoInexistenteEhRejeitado() {
        assertThrows(BusinessException.class, () -> editorService.config(DOCUMENTO_ID, ator("admin", br.com.ged.user.Role.ADMIN), "banana"));
    }

    @Test
    void documentoInexistenteEhRejeitado() {
        when(documentRepository.findById(99L)).thenReturn(Optional.empty());

        assertThrows(NotFoundException.class, () -> editorService.config(99L, ator("admin", br.com.ged.user.Role.ADMIN), "view"));
    }

    private br.com.ged.config.AuthenticatedUser ator(String username, br.com.ged.user.Role role) {
        return new br.com.ged.config.AuthenticatedUser(1L, username, role, 7L);
    }

    private Document documento(String nome, DocumentKind kind, DocumentStatus status) {
        Document document = new Document(folder, nome, kind, "admin");
        document.setId(DOCUMENTO_ID);
        document.setStatus(status);
        document.setCurrentVersion(1);
        return document;
    }

    private void versionar(Document document, int numero) {
        document.setCurrentVersion(numero);
        DocumentVersion version = new DocumentVersion(document, numero, DOCUMENTO_ID + "/v" + numero + "-arquivo",
                10, "abc", null, "admin");
        version.setId(100L + numero);
        when(versionRepository.findTopByDocumentOrderByVersionNumberDesc(document)).thenReturn(Optional.of(version));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> documentoConfig(Map<String, Object> config) {
        return (Map<String, Object>) config.get("document");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> editorConfig(Map<String, Object> config) {
        return (Map<String, Object>) config.get("editorConfig");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> permissions(Map<String, Object> config) {
        return (Map<String, Object>) documentoConfig(config).get("permissions");
    }
}
