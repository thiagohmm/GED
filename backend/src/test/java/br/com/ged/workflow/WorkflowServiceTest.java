package br.com.ged.workflow;

import br.com.ged.document.Document;
import br.com.ged.document.DocumentKind;
import br.com.ged.document.DocumentResponse;
import br.com.ged.document.DocumentRepository;
import br.com.ged.document.DocumentStatus;
import br.com.ged.document.DocumentStatusHistoryService;
import br.com.ged.exception.BusinessException;
import br.com.ged.folder.Folder;
import br.com.ged.review.Review;
import br.com.ged.review.ReviewService;
import br.com.ged.user.Role;
import br.com.ged.user.RoleChecker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WorkflowServiceTest {

    private static final long DOCUMENTO_ID = 7L;

    @Mock
    private DocumentRepository documentRepository;

    @Mock
    private ReviewService reviewService;

    @Mock
    private RoleChecker roleChecker;

    @Mock
    private DocumentStatusHistoryService statusHistoryService;

    private WorkflowService workflowService;
    private Document document;

    @BeforeEach
    void setUp() {
        workflowService = new WorkflowService(documentRepository, reviewService, roleChecker, statusHistoryService);
        Folder pasta = new Folder("Contratos", null, "admin");
        pasta.setId(1L);
        document = new Document(pasta, "peticao.docx", DocumentKind.WORD, "admin");
        document.setId(DOCUMENTO_ID);
        document.setStatus(DocumentStatus.EDITANDO);
        when(documentRepository.findById(DOCUMENTO_ID)).thenReturn(Optional.of(document));
        when(statusHistoryService.changeStatus(any(), any(), any(), any()))
                .thenAnswer(invocation -> DocumentResponse.from(document));
    }

    @Test
    void envioParaRevisaoAbreRodadaSemCriarVersaoDeArquivo() {
        Review rodadaAberta = new Review(document, 1, 1, 1, "admin");
        when(reviewService.openReview(document, "admin")).thenReturn(rodadaAberta);

        workflowService.requestStatusChange(DOCUMENTO_ID, DocumentStatus.EM_REVISAO, null, "admin");

        verify(statusHistoryService).changeStatus(document, DocumentStatus.EM_REVISAO, "Revisao #1 aberta", "admin");
    }

    @Test
    void saidaManualDeEmRevisaoEhBloqueada() {
        document.setStatus(DocumentStatus.EM_REVISAO);

        assertThrows(BusinessException.class,
                () -> workflowService.requestStatusChange(DOCUMENTO_ID, DocumentStatus.EDITANDO, null, "revisora"));
        assertThrows(BusinessException.class,
                () -> workflowService.requestStatusChange(DOCUMENTO_ID, DocumentStatus.PRONTO_PARA_PROTOCOLO, null, "revisora"));
    }

    @Test
    void protocolarSemPapelEhBarrado() {
        document.setStatus(DocumentStatus.PRONTO_PARA_PROTOCOLO);
        doThrow(new AccessDeniedException("Perfil sem permissao."))
                .when(roleChecker).requireAny(any());

        assertThrows(AccessDeniedException.class,
                () -> workflowService.requestStatusChange(DOCUMENTO_ID, DocumentStatus.PROTOCOLADO, null, "maria"));
        verify(reviewService, never()).openReview(any(), any());
    }

    @Test
    void protocoladorProtocolaSemCriarVersaoDeArquivo() {
        document.setStatus(DocumentStatus.PRONTO_PARA_PROTOCOLO);
        doNothing().when(roleChecker).requireAny(any());

        workflowService.requestStatusChange(DOCUMENTO_ID, DocumentStatus.PROTOCOLADO, "numeracao", "pedro");

        verify(statusHistoryService).changeStatus(document, DocumentStatus.PROTOCOLADO, "numeracao", "pedro");
        verify(reviewService, never()).openReview(any(), any());
    }

    @Test
    void revisorReabreDocumentoProtocolado() {
        document.setStatus(DocumentStatus.PROTOCOLADO);

        workflowService.requestStatusChange(DOCUMENTO_ID, DocumentStatus.EDITANDO, null, "revisora");

        verify(statusHistoryService).changeStatus(document, DocumentStatus.EDITANDO, null, "revisora");
    }

    @Test
    void transicaoNaoListadaEhRejeitada() {
        document.setStatus(DocumentStatus.PROTOCOLADO);

        assertThrows(BusinessException.class,
                () -> workflowService.requestStatusChange(DOCUMENTO_ID, DocumentStatus.PROTOCOLADO, null, "admin"));
    }

    @Test
    void opcoesDeTransicaoRespeitamOPapelDoUsuario() {
        document.setStatus(DocumentStatus.PRONTO_PARA_PROTOCOLO);
        when(roleChecker.currentHasAny(any())).thenReturn(false);
        assertTrue(workflowService.allowedTransitionsFor(DocumentStatus.PRONTO_PARA_PROTOCOLO).isEmpty());

        when(roleChecker.currentHasAny(any())).thenReturn(true);
        assertEquals(List.of(DocumentStatus.PROTOCOLADO),
                workflowService.allowedTransitionsFor(DocumentStatus.PRONTO_PARA_PROTOCOLO));

        assertTrue(workflowService.allowedTransitionsFor(DocumentStatus.EM_REVISAO).isEmpty());
    }
}
