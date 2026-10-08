package br.com.ged.review;

import br.com.ged.document.Document;
import br.com.ged.document.DocumentKind;
import br.com.ged.document.DocumentResponse;
import br.com.ged.document.DocumentRepository;
import br.com.ged.document.DocumentStatusHistoryService;
import br.com.ged.document.DocumentStatus;
import br.com.ged.exception.ConflictException;
import br.com.ged.folder.Folder;
import br.com.ged.folder.FolderService;
import br.com.ged.user.RoleChecker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.access.AccessDeniedException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReviewServiceTest {

    private static final long DOCUMENTO_ID = 7L;
    private static final long PASTA_ID = 1L;

    @Mock
    private ReviewRepository reviewRepository;

    @Mock
    private ReviewCommentRepository commentRepository;

    @Mock
    private DocumentRepository documentRepository;

    @Mock
    private FolderService folderService;

    @Mock
    private RoleChecker roleChecker;

    @Mock
    private DocumentStatusHistoryService statusHistoryService;

    private ReviewService reviewService;
    private Document document;

    @BeforeEach
    void setUp() {
        reviewService = new ReviewService(reviewRepository, commentRepository, documentRepository,
                folderService, roleChecker, statusHistoryService);
        Folder pasta = new Folder("Contratos", null, "admin");
        pasta.setId(PASTA_ID);
        document = new Document(pasta, "peticao.docx", DocumentKind.WORD, "maria");
        document.setId(DOCUMENTO_ID);
        document.setStatus(DocumentStatus.EM_REVISAO);
        document.setCurrentVersion(2);
        when(folderService.pathOf(PASTA_ID)).thenReturn("Contratos");
    }

    @Test
    void primeiraRodadaCobreDaVersao1AAtual() {
        when(reviewRepository.findTopByDocumentOrderByRoundDesc(document)).thenReturn(Optional.empty());
        when(reviewRepository.save(any(Review.class))).thenAnswer(invoke -> invoke.getArgument(0));

        Review aberta = reviewService.openReview(document, "maria");

        assertEquals(1, aberta.getRound());
        assertEquals(1, aberta.getFromVersion());
        assertEquals(2, aberta.getToVersion());
        assertEquals(ReviewVerdict.PENDENTE, aberta.getVerdict());
    }

    @Test
    void segundaRodadaCobreDaUltimaVersaoDecididaAAtual() {
        Review anterior = reviewDecidida(1, 1, 2);
        when(reviewRepository.findTopByDocumentOrderByRoundDesc(anterior.getDocument())).thenReturn(Optional.of(anterior));
        document.setCurrentVersion(4);
        when(reviewRepository.save(any(Review.class))).thenAnswer(invoke -> invoke.getArgument(0));

        Review aberta = reviewService.openReview(document, "maria");

        assertEquals(2, aberta.getRound());
        assertEquals(2, aberta.getFromVersion());
        assertEquals(4, aberta.getToVersion());
    }

    @Test
    void aprovacaoAtualizaStatusSemCriarVersaoDeArquivo() {
        Review review = reviewPendente(1, 1, 2);
        when(reviewRepository.findById(50L)).thenReturn(Optional.of(review));
        when(statusHistoryService.changeStatus(any(), any(), any(), any()))
                .thenAnswer(invocation -> DocumentResponse.from(document));

        reviewService.decide(50L, new DecideReviewRequest(ReviewVerdict.APROVADO, "Revisado com clareza"), "revisora");

        verify(statusHistoryService).changeStatus(document, DocumentStatus.PRONTO_PARA_PROTOCOLO,
                "Status: EM_REVISAO -> PRONTO_PARA_PROTOCOLO - Revisao #1: APROVADO - Revisado com clareza", "revisora");
        assertEquals(ReviewVerdict.APROVADO, review.getVerdict());
        assertEquals("revisora", review.getDecidedBy());
        assertEquals("Revisado com clareza", review.getSummary());
    }

    @Test
    void solicitacaoDeCorrecoesDevolveParaEdicao() {
        Review review = reviewPendente(1, 1, 2);
        when(reviewRepository.findById(51L)).thenReturn(Optional.of(review));
        when(statusHistoryService.changeStatus(any(), any(), any(), any()))
                .thenAnswer(invocation -> DocumentResponse.from(document));

        reviewService.decide(51L, new DecideReviewRequest(ReviewVerdict.CORRECOES_SOLICITADAS, "Clausula 3 sem valor"), "revisora");

        verify(statusHistoryService).changeStatus(document, DocumentStatus.EDITANDO,
                "Status: EM_REVISAO -> EDITANDO - Revisao #1: CORRECOES_SOLICITADAS - Clausula 3 sem valor", "revisora");
    }

    @Test
    void decidirSemPapelEhBarrado() {
        Review review = reviewPendente(1, 1, 2);
        when(reviewRepository.findById(52L)).thenReturn(Optional.of(review));
        doThrow(new AccessDeniedException("Perfil sem permissao."))
                .when(roleChecker).requireAny(any());

        assertThrows(AccessDeniedException.class,
                () -> reviewService.decide(52L, new DecideReviewRequest(ReviewVerdict.APROVADO, null), "maria"));
    }

    @Test
    void decidirRodadaJaDecididaGeraConflito() {
        Review review = reviewDecidida(1, 1, 2);
        review.setDocument(document);
        when(reviewRepository.findById(53L)).thenReturn(Optional.of(review));

        assertThrows(ConflictException.class,
                () -> reviewService.decide(53L, new DecideReviewRequest(ReviewVerdict.APROVADO, null), "revisora"));
    }

    @Test
    void comentarAposDecisaoGeraConflito() {
        Review review = reviewDecidida(1, 1, 2);
        review.setDocument(document);
        when(reviewRepository.findById(54L)).thenReturn(Optional.of(review));

        assertThrows(ConflictException.class,
                () -> reviewService.addComment(54L, new AddCommentRequest("Comentario atrasado", null, null), "maria"));
    }

    @Test
    void comentarioInlinePreservaCotacaoDaLinha() {
        Review review = reviewPendente(1, 1, 2);
        when(reviewRepository.findById(55L)).thenReturn(Optional.of(review));
        when(commentRepository.save(any(ReviewComment.class))).thenAnswer(invoke -> invoke.getArgument(0));

        ReviewCommentResponse response = reviewService.addComment(55L,
                new AddCommentRequest("Conferir este valor", 3, "Valor total: 250"), "maria");

        assertEquals("maria", response.author());
        assertEquals(3, response.diffLineIndex());
        assertEquals("Valor total: 250", response.diffLineContent());
    }

    @Test
    void filaRetornaPendentesEDecididasRecentes() {
        document.setStatus(DocumentStatus.PROTOCOLADO);
        when(reviewRepository.findByVerdictOrderByCreatedAtDesc(ReviewVerdict.PENDENTE))
                .thenReturn(List.of(reviewPendente(2, 1, 2)));
        when(reviewRepository.findTop10ByVerdictInAndDecidedAtIsNotNullOrderByDecidedAtDesc(
                List.of(ReviewVerdict.APROVADO, ReviewVerdict.CORRECOES_SOLICITADAS)))
                .thenReturn(List.of(reviewDecidida(1, 1, 2)));

        ReviewQueueResponse queue = reviewService.queue();

        assertEquals(1, queue.pending().size());
        assertEquals("Contratos", queue.pending().get(0).folderPath());
        assertEquals("peticao.docx", queue.pending().get(0).documentName());
        assertEquals(ReviewVerdict.APROVADO, queue.recentlyDecided().get(0).verdict());
    }

    private Review reviewPendente(int round, int fromVersion, int toVersion) {
        Review review = new Review(document, round, fromVersion, toVersion, "maria");
        return review;
    }

    private Review reviewDecidida(int round, int fromVersion, int toVersion) {
        Review review = new Review(document, round, fromVersion, toVersion, "maria");
        review.setVerdict(ReviewVerdict.APROVADO);
        review.setDecidedBy("revisora");
        review.setDecidedAt(Instant.now());
        return review;
    }
}
