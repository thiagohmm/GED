package br.com.ged.document;

import br.com.ged.diff.DiffCalculator;
import br.com.ged.diff.DiffType;
import br.com.ged.diff.ExcelTextExtractor;
import br.com.ged.diff.PdfTextExtractor;
import br.com.ged.diff.WordTextExtractor;
import br.com.ged.exception.BusinessException;
import br.com.ged.exception.InvalidFileTypeException;
import br.com.ged.exception.ConflictException;
import br.com.ged.exception.NoChangesDetectedException;
import br.com.ged.exception.NotFoundException;
import br.com.ged.folder.Folder;
import br.com.ged.folder.FolderService;
import br.com.ged.review.Review;
import br.com.ged.review.ReviewRepository;
import br.com.ged.review.ReviewVerdict;
import br.com.ged.search.NoopIndexingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.mockito.stubbing.Answer;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DocumentServiceTest {

    private static final long PASTA_ID = 1L;
    private static final long DOCUMENTO_ID = 10L;

    @Mock
    private DocumentRepository documentRepository;

    @Mock
    private DocumentVersionRepository versionRepository;

    @Mock
    private FolderService folderService;

    @Mock
    private ReviewRepository reviewRepository;

    @Mock
    private DocumentStatusChangeRepository statusChangeRepository;

    @TempDir
    Path tempDir;

    private DocumentService documentService;
    private Folder folder;

    @BeforeEach
    void setUp() {
        documentService = new DocumentService(
                documentRepository,
                versionRepository,
                folderService,
                new StorageService(tempDir.toString()),
                List.of(new WordTextExtractor(), new ExcelTextExtractor(), new PdfTextExtractor()),
                new DiffCalculator(),
                new ObjectMapper(),
                new NoopIndexingService(),
                reviewRepository,
                statusChangeRepository);
        folder = new Folder("Contratos", null, "admin");
        folder.setId(PASTA_ID);
        when(folderService.findById(PASTA_ID)).thenReturn(folder);
        when(documentRepository.saveAndFlush(any(Document.class))).thenAnswer(salvarComId());
        when(documentRepository.save(any(Document.class))).thenAnswer(salvarComId());
        when(versionRepository.save(any(DocumentVersion.class))).thenAnswer(salvarComId());
    }

    @Test
    void primeiroUploadCriaVersao1SemDiff() {
        when(documentRepository.findByFolderIdAndNameIgnoreCase(PASTA_ID, "proposta.docx"))
                .thenReturn(Optional.empty());

        DocumentResponse response = documentService.upload(PASTA_ID, docxUpload("proposta.docx",
                "Primeiro paragrafo"), null, "admin");

        assertEquals(1, response.currentVersion());
        assertEquals(DOCUMENTO_ID, response.id());
        DocumentVersion versao1 = versoesSalvas().get(0);
        assertEquals(1, versao1.getVersionNumber());
        assertTrue(versao1.getExtractedText().contains("Primeiro paragrafo"));
        assertNull(versao1.getDiffJson());
        assertTrue(Files.exists(tempDir.resolve("10/v1-proposta.docx")));
    }

    @Test
    void reuploadMesmoNomeDeWordCriaVersao2ComDiff() {
        when(documentRepository.findByFolderIdAndNameIgnoreCase(PASTA_ID, "proposta.docx"))
                .thenReturn(Optional.empty());
        documentService.upload(PASTA_ID, docxUpload("proposta.docx", "Primeiro paragrafo"), null, "admin");

        Document existente = documentoSalvo();
        when(documentRepository.findByFolderIdAndNameIgnoreCase(PASTA_ID, "proposta.docx"))
                .thenReturn(Optional.of(existente));
        Optional<DocumentVersion> versao1 = Optional.of(versoesSalvas().get(0));
        when(versionRepository.findTopByDocumentOrderByVersionNumberDesc(existente))
                .thenReturn(versao1);

        DocumentResponse response = documentService.upload(PASTA_ID,
                docxUpload("proposta.docx", "Primeiro paragrafo\nParagrafo final"), "Inclui o paragrafo final", "admin");

        assertEquals(2, response.currentVersion());
        List<DocumentVersion> versoes = versoesSalvas();
        DocumentVersion versao2 = versoes.get(1);
        assertEquals(2, versao2.getVersionNumber());
        assertEquals("Inclui o paragrafo final", versao2.getChangeDescription());
        assertNotNull(versao2.getDiffJson());
        assertTrue(versao2.getDiffJson().contains("Paragrafo final"));
        assertTrue(versao2.getDiffJson().contains("ADDED"));
        assertTrue(Files.exists(tempDir.resolve("10/v2-proposta.docx")));
    }

    @Test
    void planilhaReenviadaCriaVersao2ComDiffDeCelula() {
        when(documentRepository.findByFolderIdAndNameIgnoreCase(PASTA_ID, "planilha.xlsx"))
                .thenReturn(Optional.empty());
        documentService.upload(PASTA_ID, planilhaUpload("planilha.xlsx", "Orcamento", 100.0), null, "admin");

        Document existente = documentoSalvo();
        when(documentRepository.findByFolderIdAndNameIgnoreCase(PASTA_ID, "planilha.xlsx"))
                .thenReturn(Optional.of(existente));
        Optional<DocumentVersion> versao1 = Optional.of(versoesSalvas().get(0));
        when(versionRepository.findTopByDocumentOrderByVersionNumberDesc(existente))
                .thenReturn(versao1);

        DocumentResponse response = documentService.upload(PASTA_ID,
                planilhaUpload("planilha.xlsx", "Orcamento", 250.0), null, "admin");

        assertEquals(2, response.currentVersion());
        DocumentVersion versao2 = versoesSalvas().get(1);
        assertTrue(versao2.getDiffJson().contains("100"));
        assertTrue(versao2.getDiffJson().contains("250"));
        assertTrue(versao2.getDiffJson().contains("Orcamento!B2"));
    }


    @Test
    void rejeitaReenvioDePdfComMesmoNome() {
        Document pdf = new Document(folder, "notas.pdf", DocumentKind.PDF, "admin");
        pdf.setId(30L);
        when(documentRepository.findByFolderIdAndNameIgnoreCase(PASTA_ID, "notas.pdf"))
                .thenReturn(Optional.of(pdf));

        MockMultipartFile arquivo = pdfUpload("notas.pdf");

        assertThrows(ConflictException.class, () -> documentService.upload(PASTA_ID, arquivo, null, "admin"));
    }

    @Test
    void uploadEmDocumentoEmRevisaoFechaRevisaoPendenteERetornaAEdicao() {        Document document = new Document(folder, "peticao.docx", DocumentKind.WORD, "admin");
        document.setId(40L);
        document.setStatus(DocumentStatus.EM_REVISAO);
        when(documentRepository.findByFolderIdAndNameIgnoreCase(PASTA_ID, "peticao.docx"))
                .thenReturn(Optional.of(document));
        Review pendente = new Review(document, 1, 1, 1, "revisor");
        when(reviewRepository.findByDocumentAndVerdict(document, ReviewVerdict.PENDENTE))
                .thenReturn(List.of(pendente));

        DocumentResponse response = documentService.upload(PASTA_ID,
                docxUpload("peticao.docx", "Texto alterado"), null, "admin");

        assertEquals(2, response.currentVersion());
        assertEquals(DocumentStatus.EDITANDO, response.status());
        assertEquals(DocumentStatus.EDITANDO, document.getStatus());
        assertEquals(ReviewVerdict.SUPERADA, pendente.getVerdict());
        assertEquals("admin", pendente.getDecidedBy());
        assertNotNull(pendente.getDecidedAt());
        assertTrue(pendente.getSummary().contains("v2"));
        ArgumentCaptor<DocumentStatusChange> mudanca = ArgumentCaptor.forClass(DocumentStatusChange.class);
        verify(statusChangeRepository).save(mudanca.capture());
        assertEquals(DocumentStatus.EM_REVISAO, mudanca.getValue().getFromStatus());
        assertEquals(DocumentStatus.EDITANDO, mudanca.getValue().getToStatus());
    }

    @Test
    void saveEditedVersionEmRevisaoTambemFechaPendenteERetornaAEdicao() throws IOException {
        Document document = new Document(folder, "peticao.docx", DocumentKind.WORD, "admin");
        document.setId(44L);
        document.setStatus(DocumentStatus.EM_REVISAO);
        document.setCurrentVersion(1);
        DocumentVersion v1 = versaoConTexto(document, 1, "texto antigo");
        when(documentRepository.findById(44L)).thenReturn(Optional.of(document));
        when(versionRepository.findTopByDocumentOrderByVersionNumberDesc(document)).thenReturn(Optional.of(v1));
        Review pendente = new Review(document, 1, 1, 1, "revisor");
        when(reviewRepository.findByDocumentAndVerdict(document, ReviewVerdict.PENDENTE))
                .thenReturn(List.of(pendente));

        DocumentResponse response = documentService.saveEditedVersion(44L,
                docxUpload("peticao.docx", "texto novo").getBytes(), "admin");

        assertEquals(2, response.currentVersion());
        assertEquals(DocumentStatus.EDITANDO, document.getStatus());
        assertEquals(ReviewVerdict.SUPERADA, pendente.getVerdict());
    }

    @Test
    void uploadEmDocumentoProtocoladoReabreOFluxoEmEdicao() {
        Document document = new Document(folder, "contrato.docx", DocumentKind.WORD, "admin");
        document.setId(45L);
        document.setStatus(DocumentStatus.PROTOCOLADO);
        document.setCurrentVersion(1);
        when(documentRepository.findByFolderIdAndNameIgnoreCase(PASTA_ID, "contrato.docx"))
                .thenReturn(Optional.of(document));
        when(reviewRepository.findByDocumentAndVerdict(document, ReviewVerdict.PENDENTE))
                .thenReturn(List.of());

        DocumentResponse response = documentService.upload(PASTA_ID,
                docxUpload("contrato.docx", "Conteudo apos revisao extra"), null, "admin");

        assertEquals(2, response.currentVersion());
        assertEquals(DocumentStatus.EDITANDO, response.status());
        verify(statusChangeRepository).save(any(DocumentStatusChange.class));
        DocumentVersion versao2 = versoesSalvas().get(0);
        assertEquals(2, versao2.getVersionNumber());
        assertEquals(DocumentStatus.PROTOCOLADO, versao2.getStatus());
    }

    @Test
    void uploadEnquantoEditandoNaoGeraStatusChange() {
        when(documentRepository.findByFolderIdAndNameIgnoreCase(PASTA_ID, "proposta.docx"))
                .thenReturn(Optional.empty());
        documentService.upload(PASTA_ID, docxUpload("proposta.docx", "Primeiro paragrafo"), null, "admin");

        Document existente = documentoSalvo();
        when(documentRepository.findByFolderIdAndNameIgnoreCase(PASTA_ID, "proposta.docx"))
                .thenReturn(Optional.of(existente));
        Optional<DocumentVersion> versao1 = Optional.of(versoesSalvas().get(0));
        when(versionRepository.findTopByDocumentOrderByVersionNumberDesc(existente))
                .thenReturn(versao1);

        documentService.upload(PASTA_ID, docxUpload("proposta.docx", "Primeiro paragrafo\nParagrafo extra"), null, "admin");

        verify(statusChangeRepository, never()).save(any(DocumentStatusChange.class));
        assertEquals(DocumentStatus.EDITANDO, existente.getStatus());
    }

    @Test
    void moverDocumentoEntrePastasAtualizaPastaEReindexa() {
        Document documento = new Document(folder, "peticao.docx", DocumentKind.WORD, "admin");
        documento.setId(50L);
        documento.setStatus(DocumentStatus.EDITANDO);
        documento.setCurrentVersion(1);
        Folder destino = new Folder("Processos", null, "admin");
        destino.setId(2L);
        when(documentRepository.findById(50L)).thenReturn(Optional.of(documento));
        when(folderService.findById(2L)).thenReturn(destino);
        when(documentRepository.findByFolderIdAndNameIgnoreCase(2L, "peticao.docx")).thenReturn(Optional.empty());
        when(folderService.pathOf(2L)).thenReturn("Processos");

        DocumentResponse response = documentService.move(50L, 2L);

        assertEquals(2L, response.folderId());
        assertEquals(destino, documento.getFolder());
        assertEquals(DocumentStatus.EDITANDO, response.status());
        assertEquals(1, response.currentVersion());
    }

    @Test
    void moverComNomeDuplicadoNoDestinoRejeita() {
        Document documento = new Document(folder, "peticao.docx", DocumentKind.WORD, "admin");
        documento.setId(51L);
        Folder destino = new Folder("Processos", null, "admin");
        destino.setId(2L);
        when(documentRepository.findById(51L)).thenReturn(Optional.of(documento));
        when(folderService.findById(2L)).thenReturn(destino);
        when(documentRepository.findByFolderIdAndNameIgnoreCase(2L, "peticao.docx"))
                .thenReturn(Optional.of(new Document(destino, "peticao.docx", DocumentKind.WORD, "outro")));

        assertThrows(ConflictException.class, () -> documentService.move(51L, 2L));
        assertEquals(folder, documento.getFolder());
    }

    @Test
    void moverParaAMesmaPastaRejeita() {
        Document documento = new Document(folder, "peticao.docx", DocumentKind.WORD, "admin");
        documento.setId(52L);
        when(documentRepository.findById(52L)).thenReturn(Optional.of(documento));
        when(folderService.findById(PASTA_ID)).thenReturn(folder);

        assertThrows(BusinessException.class, () -> documentService.move(52L, PASTA_ID));
    }

    @Test
    void moverParaPastaInexistenteRejeita() {
        Document documento = new Document(folder, "peticao.docx", DocumentKind.WORD, "admin");
        documento.setId(53L);
        when(documentRepository.findById(53L)).thenReturn(Optional.of(documento));
        when(folderService.findById(99L)).thenThrow(new NotFoundException("Pasta nao encontrada."));

        assertThrows(NotFoundException.class, () -> documentService.move(53L, 99L));
    }

    @Test
    void rejeitaUploadIdenticoAVersaoAtual() {
        when(documentRepository.findByFolderIdAndNameIgnoreCase(PASTA_ID, "proposta.docx"))
                .thenReturn(Optional.empty());
        documentService.upload(PASTA_ID, docxUpload("proposta.docx", "Texto sem mudanca"), null, "admin");

        Document existente = documentoSalvo();
        when(documentRepository.findByFolderIdAndNameIgnoreCase(PASTA_ID, "proposta.docx"))
                .thenReturn(Optional.of(existente));
        Optional<DocumentVersion> versao1 = Optional.of(versoesSalvas().get(0));
        when(versionRepository.findTopByDocumentOrderByVersionNumberDesc(existente))
                .thenReturn(versao1);

        assertThrows(NoChangesDetectedException.class,
                () -> documentService.upload(PASTA_ID, docxUpload("proposta.docx", "Texto sem mudanca"), "resalvo", "admin"));
        assertEquals(1, versoesSalvas().size());
        assertFalse(Files.exists(tempDir.resolve("10/v2-proposta.docx")), "Arquivo rejeitado não deve permanecer em disco");
    }

    @Test
    void aceitaUploadDePdfValidoArmazenandoTexto() {
        when(documentRepository.findByFolderIdAndNameIgnoreCase(PASTA_ID, "acordao.pdf"))
                .thenReturn(Optional.empty());

        DocumentResponse response = documentService.upload(PASTA_ID, pdfUploadComTexto("acordao.pdf"), null, "admin");

        assertEquals(1, response.currentVersion());
        DocumentVersion versao1 = versoesSalvas().get(0);
        assertTrue(versao1.getExtractedText().contains("Termo de acordo"));
        assertNull(versao1.getDiffJson());
    }

    @Test
    void aceitaUploadDeImagemSemExtracaoDeTexto() {
        when(documentRepository.findByFolderIdAndNameIgnoreCase(PASTA_ID, "cpf.jpg"))
                .thenReturn(Optional.empty());
        MockMultipartFile foto = new MockMultipartFile("file", "cpf.jpg", "image/jpeg", new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF});

        DocumentResponse response = documentService.upload(PASTA_ID, foto, "cpf", "admin");

        assertEquals(DocumentKind.IMAGE, response.kind());
        assertEquals(1, response.currentVersion());
        assertEquals("", versoesSalvas().get(0).getExtractedText());
    }

    @Test
    void rejeitaNomeExistenteComFormatoDiferente() {
        Document word = new Document(folder, "planilha.xlsx", DocumentKind.WORD, "admin");
        word.setId(31L);
        when(documentRepository.findByFolderIdAndNameIgnoreCase(PASTA_ID, "planilha.xlsx"))
                .thenReturn(Optional.of(word));

        assertThrows(ConflictException.class,
                () -> documentService.upload(PASTA_ID, planilhaUpload("planilha.xlsx", "Aba", 1.0), null, "admin"));
    }

    @Test
    void rejeitaTipoNaoSuportado() {
        MockMultipartFile arquivo = new MockMultipartFile("file", "leia.txt", "text/plain", "texto".getBytes());

        assertThrows(InvalidFileTypeException.class, () -> documentService.upload(PASTA_ID, arquivo, null, "admin"));
    }

    @Test
    void rejeitaUploadEmPastaInexistente() {
        when(folderService.findById(99L)).thenThrow(new NotFoundException("Pasta nao encontrada."));

        assertThrows(NotFoundException.class,
                () -> documentService.upload(99L, docxUpload("qualquer.docx", "texto"), null, "admin"));
    }

    @Test
    void diffCustomizadoEntreVersoesSemDiffArmazenado() {
        Document document = new Document(folder, "proposta.docx", DocumentKind.WORD, "admin");
        document.setId(40L);
        document.setCurrentVersion(3);
        DocumentVersion v1 = versaoConTexto(document, 1, "um");
        DocumentVersion v3 = versaoConTexto(document, 3, "um\ndois\ntres");
        when(documentRepository.findById(40L)).thenReturn(Optional.of(document));
        when(versionRepository.findByDocumentAndVersionNumber(document, 1)).thenReturn(Optional.of(v1));
        when(versionRepository.findByDocumentAndVersionNumber(document, 3)).thenReturn(Optional.of(v3));

        DiffResponse diff = documentService.diff(40L, 1, 3);

        assertEquals(1, diff.fromVersion());
        assertEquals(3, diff.toVersion());
        assertEquals(DiffType.UNCHANGED, diff.lines().get(0).type());
        assertEquals("um", diff.lines().get(0).content());
        assertTrue(diff.lines().stream().anyMatch(line -> line.type() == DiffType.ADDED && "dois".equals(line.content())));
    }

    @Test
    void rejeicaoParaDiffComMesmaVersao() {
        Document document = new Document(folder, "proposta.docx", DocumentKind.WORD, "admin");
        document.setId(41L);
        document.setCurrentVersion(1);
        when(documentRepository.findById(41L)).thenReturn(Optional.of(document));

        assertThrows(BusinessException.class, () -> documentService.diff(41L, null, null));
    }

    private DocumentVersion versaoConTexto(Document document, int numero, String texto) {
        DocumentVersion version = new DocumentVersion(document, numero, "40/v" + numero + "-proposta.docx",
                10, "abc", null, "admin");
        version.setExtractedText(texto);
        return version;
    }

    private Document documentoSalvo() {
        ArgumentCaptor<Document> captor = ArgumentCaptor.forClass(Document.class);
        verify(documentRepository, atLeastOnce()).saveAndFlush(captor.capture());
        return captor.getValue();
    }

    private List<DocumentVersion> versoesSalvas() {
        ArgumentCaptor<DocumentVersion> captor = ArgumentCaptor.forClass(DocumentVersion.class);
        verify(versionRepository, atLeastOnce()).save(captor.capture());
        return captor.getAllValues();
    }

    private Answer<Object> salvarComId() {
        return (InvocationOnMock invocation) -> {
            Object entity = invocation.getArgument(0);
            if (entity instanceof Document document) {
                document.setId(DOCUMENTO_ID);
            } else if (entity instanceof DocumentVersion version) {
                version.setId(100L + version.getVersionNumber());
            }
            return entity;
        };
    }

    private MockMultipartFile docxUpload(String name, String texto) {
        try (XWPFDocument document = new XWPFDocument()) {
            document.createParagraph().createRun().setText(texto);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            document.write(bytes);
            return new MockMultipartFile("file", name,
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document", bytes.toByteArray());
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private MockMultipartFile planilhaUpload(String name, String aba, double valor) {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            workbook.createSheet(aba).createRow(1).createCell(1).setCellValue(valor);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            workbook.write(bytes);
            return new MockMultipartFile("file", name,
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", bytes.toByteArray());
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private MockMultipartFile pdfUpload(String name) {
        return new MockMultipartFile("file", name, "application/pdf", new byte[] {45, 46});
    }

    private MockMultipartFile pdfUploadComTexto(String name) {
        try (PDDocument pdf = new PDDocument()) {
            PDPage pagina = new PDPage();
            pdf.addPage(pagina);
            try (PDPageContentStream conteudo = new PDPageContentStream(pdf, pagina)) {
                conteudo.beginText();
                conteudo.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                conteudo.newLineAtOffset(25, 500);
                conteudo.showText("Termo de acordo com clausula de rescisao");
                conteudo.endText();
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            pdf.save(bytes);
            return new MockMultipartFile("file", name, "application/pdf", bytes.toByteArray());
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
