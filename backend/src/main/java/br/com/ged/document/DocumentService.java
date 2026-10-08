package br.com.ged.document;

import br.com.ged.diff.DiffCalculator;
import br.com.ged.diff.DiffLine;
import br.com.ged.diff.TextExtractor;
import br.com.ged.exception.BusinessException;
import br.com.ged.exception.ConflictException;
import br.com.ged.exception.InvalidFileTypeException;
import br.com.ged.exception.NoChangesDetectedException;
import br.com.ged.exception.NotFoundException;
import br.com.ged.folder.Folder;
import br.com.ged.folder.FolderService;
import br.com.ged.review.Review;
import br.com.ged.review.ReviewRepository;
import br.com.ged.review.ReviewVerdict;
import br.com.ged.search.SearchGateway;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.regex.Pattern;

@Service
@Slf4j
public class DocumentService {

    private static final String LINE_SEPARATOR = "\n";
    private static final TypeReference<List<DiffLine>> DIFF_LINES_TYPE = new TypeReference<>() {};
    private static final int MAX_FILE_NAME_LENGTH = 180;
    private static final Pattern ILLEGAL_CHARACTERS = Pattern.compile("[^a-zA-Z0-9._ -]");
    private static final Pattern PATH_CLIMBING = Pattern.compile("\\.{2,}");

    private final DocumentRepository documentRepository;
    private final DocumentVersionRepository versionRepository;
    private final FolderService folderService;
    private final StorageService storageService;
    private final List<TextExtractor> textExtractors;
    private final DiffCalculator diffCalculator;
    private final ObjectMapper objectMapper;
    private final SearchGateway searchGateway;
    private final ReviewRepository reviewRepository;
    private final DocumentStatusChangeRepository statusChangeRepository;

    public DocumentService(DocumentRepository documentRepository,
                           DocumentVersionRepository versionRepository,
                           FolderService folderService,
                           StorageService storageService,
                           List<TextExtractor> textExtractors,
                           DiffCalculator diffCalculator,
                           ObjectMapper objectMapper,
                           SearchGateway searchGateway,
                           ReviewRepository reviewRepository,
                           DocumentStatusChangeRepository statusChangeRepository) {
        this.documentRepository = documentRepository;
        this.versionRepository = versionRepository;
        this.folderService = folderService;
        this.storageService = storageService;
        this.textExtractors = textExtractors;
        this.diffCalculator = diffCalculator;
        this.objectMapper = objectMapper;
        this.searchGateway = searchGateway;
        this.reviewRepository = reviewRepository;
        this.statusChangeRepository = statusChangeRepository;
    }

    @Transactional
    public DocumentResponse upload(Long folderId, MultipartFile file, String changeDescription, String username) {
        Folder folder = folderService.findById(folderId);
        String documentName = sanitizeFileName(file.getOriginalFilename());
        DocumentKind kind = DocumentKind.fromFileName(documentName)
                .orElseThrow(() -> new InvalidFileTypeException(documentName));

        return documentRepository.findByFolderIdAndNameIgnoreCase(folderId, documentName)
                .<DocumentResponse>map(document -> addVersionTo(document, kind, file, changeDescription, username))
                .orElseGet(() -> createDocument(folder, documentName, kind, file, changeDescription, username));
    }

    @Transactional(readOnly = true)
    public List<DocumentResponse> listInFolder(Long folderId) {
        folderService.findById(folderId);
        return documentRepository.findByFolderIdSortedByName(folderId).stream()
                .map(DocumentResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public DocumentResponse detail(Long documentId) {
        return DocumentResponse.from(findById(documentId));
    }

    @Transactional
    public DocumentResponse updateTags(Long documentId, List<String> requestedTags) {
        Document document = findById(documentId);
        List<String> tags = requestedTags == null ? List.of() : requestedTags.stream()
                .map(String::strip)
                .filter(tag -> !tag.isEmpty())
                .distinct()
                .toList();
        if (tags.size() > 20 || tags.stream().anyMatch(tag -> tag.length() > 40)) {
            throw new BusinessException("Use ate 20 tags com no maximo 40 caracteres cada.");
        }
        document.setTags(tags);
        return DocumentResponse.from(document);
    }

    @Transactional(readOnly = true)
    public List<DocumentVersionResponse> versions(Long documentId) {
        Document document = findById(documentId);
        return versionRepository.findByDocumentOrderByVersionNumberDesc(document).stream()
                .map(DocumentVersionResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public DiffResponse diff(Long documentId, Integer requestedFrom, Integer requestedTo) {
        Document document = findById(documentId);
        rejectDiffForNonVersionable(document);

        int to = requestedTo == null ? document.getCurrentVersion() : requestedTo;
        int from = requestedFrom == null ? Math.max(1, to - 1) : requestedFrom;
        if (from == to) {
            throw new BusinessException("Selecione duas versoes diferentes para comparar.");
        }
        DocumentVersion fromVersion = loadVersion(document, from);
        DocumentVersion toVersion = loadVersion(document, to);
        return new DiffResponse(from, to, diffLines(document, fromVersion, toVersion));
    }

    @Transactional(readOnly = true)
    public VersionDownload downloadVersion(Long versionId) {
        DocumentVersion version = versionRepository.findById(versionId)
                .orElseThrow(() -> new NotFoundException("Versao nao encontrada."));
        Path stored = storageService.resolveStored(version.getFilePath());
        try {
            return new VersionDownload(version.getDocument().getName(), Files.readAllBytes(stored));
        } catch (IOException exception) {
            throw new UncheckedIOException("Falha ao abrir o arquivo armazenado.", exception);
        }
    }

    @Transactional
    public void delete(Long documentId) {
        Document document = findById(documentId);
        documentRepository.delete(document);
        storageService.deleteDocumentDirectory(documentId);
        searchGateway.deleteVersions(documentId);
    }

    @Transactional
    public DocumentResponse updateStatus(Document document, DocumentStatus newStatus) {
        document.setStatus(newStatus);
        for (DocumentVersion version : document.getVersions()) {
            searchGateway.indexVersion(version, folderPathOf(document));
        }
        return DocumentResponse.from(document);
    }

    @Transactional
    public DocumentResponse move(Long documentId, Long targetFolderId) {
        Document document = findById(documentId);
        Folder target = folderService.findById(targetFolderId);
        if (document.getFolder().getId().equals(target.getId())) {
            throw new BusinessException("O documento ja esta nessa pasta.");
        }
        documentRepository.findByFolderIdAndNameIgnoreCase(target.getId(), document.getName())
                .ifPresent(conflicting -> {
                    throw new ConflictException("Ja existe um documento com este nome na pasta destino.");
                });
        document.setFolder(target);
        updateStatus(document, document.getStatus());
        return DocumentResponse.from(document);
    }

    private DocumentResponse addVersionTo(Document document, DocumentKind kind, MultipartFile file,
                                          String changeDescription, String username) {
        if (document.getKind() != kind) {
            throw new ConflictException("Ja existe um documento com este nome na pasta em outro formato.");
        }
        if (!kind.isVersionable()) {
            throw new ConflictException("Imagens e PDF aceitam apenas um arquivo por nome. Renomeie o arquivo.");
        }
        Optional<DocumentVersion> previous = versionRepository.findTopByDocumentOrderByVersionNumberDesc(document);
        int versionNumber = document.getCurrentVersion() + 1;
        createVersion(document, versionNumber, file, changeDescription, username, previous);
        document.setCurrentVersion(versionNumber);
        supersedePendingReview(document, versionNumber, username);
        return DocumentResponse.from(document);
    }

    private void supersedePendingReview(Document document, int versionNumber, String username) {
        List<Review> pendings = reviewRepository.findByDocumentAndVerdict(document, ReviewVerdict.PENDENTE);
        String note = pendings.isEmpty()
                ? "Nova versao v" + versionNumber + " recebida - fluxo reaberto em edicao."
                : "Nova versao v" + versionNumber + " recebida - revisao pendente superada.";
        pendings.forEach(review -> {
            review.setVerdict(ReviewVerdict.SUPERADA);
            review.setDecidedBy(username);
            review.setDecidedAt(Instant.now());
            review.setSummary(note);
        });
        reviewRepository.saveAll(pendings);
        if (document.getStatus() != DocumentStatus.EDITANDO) {
            DocumentStatusChange change = statusChangeRepository.save(new DocumentStatusChange(
                    document, document.getStatus(), DocumentStatus.EDITANDO, note, username));
            document.getStatusChanges().add(change);
            updateStatus(document, DocumentStatus.EDITANDO);
        }
    }

    private DocumentResponse createDocument(Folder folder, String documentName, DocumentKind kind,
                                            MultipartFile file, String changeDescription, String username) {
        Document document = documentRepository.saveAndFlush(new Document(folder, documentName, kind, username));
        createVersion(document, 1, file, changeDescription, username, Optional.empty());
        return DocumentResponse.from(document);
    }

    private void createVersion(Document document, int versionNumber, MultipartFile file,
                               String changeDescription, String username,
                               Optional<DocumentVersion> previousVersion) {
        StoredFile stored = storageService.store(document.getId(), versionNumber, document.getName(), file);
        if (previousVersion.map(version -> version.getSha256().equals(stored.sha256())).orElse(false)) {
            storageService.deleteStoredFile(stored.relativePath());
            throw new NoChangesDetectedException();
        }
        DocumentVersion version = new DocumentVersion(document, versionNumber, stored.relativePath(),
                stored.sizeBytes(), stored.sha256(), cleanChangeDescription(changeDescription), username);
        version.setStatus(document.getStatus());
        try {
            attachAnalysis(version, previousVersion);
        } catch (RuntimeException exception) {
            storageService.deleteStoredFile(stored.relativePath());
            throw exception;
        }
        versionRepository.save(version);
        document.getVersions().add(version);
        searchGateway.indexVersion(version, folderPathOf(document));
    }

    @Transactional
    public DocumentResponse saveEditedVersion(Long documentId, byte[] content, String username) {
        Document document = findById(documentId);
        if (document.getKind() != DocumentKind.WORD && document.getKind() != DocumentKind.EXCEL
                && document.getKind() != DocumentKind.PDF) {
            throw new ConflictException("O editor integrado aceita Word, Excel e PDF.");
        }
        DocumentVersion previous = versionRepository.findTopByDocumentOrderByVersionNumberDesc(document)
                .orElseThrow(() -> new IllegalStateException("Documento sem versao registrada."));
        int versionNumber = document.getCurrentVersion() + 1;
        StoredFile stored = storageService.store(documentId, versionNumber, document.getName(), content);
        if (stored.sha256().equals(previous.getSha256())) {
            storageService.deleteStoredFile(stored.relativePath());
            return DocumentResponse.from(document);
        }
        DocumentVersion version = new DocumentVersion(document, versionNumber, stored.relativePath(),
                stored.sizeBytes(), stored.sha256(), "Editado no GED", username);
        version.setStatus(document.getStatus());
        try {
            attachAnalysis(version, Optional.of(previous));
        } catch (RuntimeException exception) {
            storageService.deleteStoredFile(stored.relativePath());
            throw exception;
        }
        versionRepository.save(version);
        document.getVersions().add(version);
        document.setCurrentVersion(versionNumber);
        supersedePendingReview(document, versionNumber, username);
        searchGateway.indexVersion(version, folderPathOf(document));
        return DocumentResponse.from(document);
    }

    private void attachAnalysis(DocumentVersion version, Optional<DocumentVersion> previousVersion) {
        List<String> newLines = extractLines(version);
        version.setExtractedText(String.join(LINE_SEPARATOR, newLines));
        if (!version.getDocument().getKind().isVersionable()) {
            return;
        }
        previousVersion
                .filter(hasExtractedText())
                .ifPresent(previous -> {
                    List<String> oldLines = normalizeLines(previous.getExtractedText());
                    if (oldLines.equals(normalizeLines(version.getExtractedText()))) {
                        throw new NoChangesDetectedException();
                    }
                    version.setDiffJson(toJson(diffCalculator.calculate(oldLines, newLines)));
                });
    }

    private Predicate<DocumentVersion> hasExtractedText() {
        return version -> version.getExtractedText() != null;
    }

    private String folderPathOf(Document document) {
        return folderService.pathOf(document.getFolder().getId());
    }

    private List<String> normalizeLines(String extractedText) {
        return extractedText.isEmpty() ? List.of() : List.of(extractedText.split(LINE_SEPARATOR, -1));
    }

    private List<String> extractLines(DocumentVersion version) {
        String extension = extensionOf(version.getDocument().getName());
        Optional<TextExtractor> extractor = textExtractors.stream()
                .filter(candidate -> candidate.extensions().contains(extension))
                .findFirst();
        if (extractor.isEmpty()) {
            if (!version.getDocument().getKind().isVersionable()) {
                return List.of();
            }
            throw new BusinessException("Nao ha extrator de texto para o formato do documento.");
        }
        boolean tolerateFailure = !version.getDocument().getKind().isVersionable();
        try {
            return extractor.get().extract(storageService.resolveStored(version.getFilePath()));
        } catch (BusinessException exception) {
            if (tolerateFailure) {
                log.warn("Texto do arquivo {} nao pode ser extraido para a busca: {}",
                        version.getDocument().getName(), exception.getMessage());
                return List.of();
            }
            throw exception;
        }
    }

    private List<DiffLine> diffLines(Document document, DocumentVersion from, DocumentVersion to) {
        boolean hasStoredDiff = to.getDiffJson() != null
                && to.getVersionNumber() == document.getCurrentVersion()
                && to.getVersionNumber() == from.getVersionNumber() + 1;
        if (hasStoredDiff) {
            return readJson(to.getDiffJson());
        }
        return diffCalculator.calculate(requireLines(from), requireLines(to));
    }

    private List<String> requireLines(DocumentVersion version) {
        String text = version.getExtractedText();
        if (text == null) {
            throw new BusinessException("O texto desta versao nao pode ser recuperado para comparacao.");
        }
        return normalizeLines(text);
    }

    private void rejectDiffForNonVersionable(Document document) {
        if (!document.getKind().isVersionable()) {
            throw new BusinessException(
                    "Comparacao de alteracoes disponivel apenas para documentos Word e Excel.");
        }
    }

    private DocumentVersion loadVersion(Document document, int versionNumber) {
        return versionRepository.findByDocumentAndVersionNumber(document, versionNumber)
                .orElseThrow(() -> new NotFoundException("Versao " + versionNumber + " nao encontrada."));
    }

    private Document findById(Long documentId) {
        return documentRepository.findById(documentId)
                .orElseThrow(() -> new NotFoundException("Documento nao encontrado."));
    }

    private String cleanChangeDescription(String changeDescription) {
        if (changeDescription == null || changeDescription.isBlank()) {
            return null;
        }
        return changeDescription.strip();
    }

    private String extensionOf(String documentName) {
        int dotIndex = documentName.lastIndexOf('.');
        if (dotIndex == -1 || dotIndex == documentName.length() - 1) {
            return null;
        }
        return documentName.substring(dotIndex + 1).toLowerCase();
    }

    private String toJson(List<DiffLine> diffLines) {
        try {
            return objectMapper.writeValueAsString(diffLines);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Falha ao registrar as alteracoes.");
        }
    }

    private List<DiffLine> readJson(String diffJson) {
        try {
            return objectMapper.readValue(diffJson, DIFF_LINES_TYPE);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Falha ao ler as alteracoes registradas.");
        }
    }

    private String sanitizeFileName(String rawFileName) {
        if (rawFileName == null || rawFileName.isBlank()) {
            throw new InvalidFileTypeException(null);
        }
        String name = rawFileName.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).strip();
        name = ILLEGAL_CHARACTERS.matcher(name).replaceAll("_");
        name = PATH_CLIMBING.matcher(name).replaceAll("_");
        name = name.replaceAll("^\\.+", "").strip();
        if (name.isBlank() || !name.contains(".")) {
            throw new InvalidFileTypeException(name.isBlank() ? null : name);
        }
        if (name.length() > MAX_FILE_NAME_LENGTH) {
            int dotIndex = name.lastIndexOf('.');
            String extension = name.substring(dotIndex);
            name = name.substring(0, MAX_FILE_NAME_LENGTH - extension.length()) + extension;
        }
        return name;
    }
}
