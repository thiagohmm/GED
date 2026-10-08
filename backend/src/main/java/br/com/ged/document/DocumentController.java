package br.com.ged.document;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import br.com.ged.workflow.WorkflowService;
import jakarta.validation.Valid;

import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/api")
public class DocumentController {

    private final DocumentService documentService;
    private final WorkflowService workflowService;
    private final DocumentStatusHistoryService statusHistoryService;

    public DocumentController(DocumentService documentService, WorkflowService workflowService,
                              DocumentStatusHistoryService statusHistoryService) {
        this.documentService = documentService;
        this.workflowService = workflowService;
        this.statusHistoryService = statusHistoryService;
    }

    @PostMapping(value = "/folders/{folderId}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public DocumentResponse upload(@PathVariable Long folderId,
                                   @RequestParam("file") MultipartFile file,
                                   @RequestParam(value = "changeDescription", required = false) String changeDescription,
                                   @AuthenticationPrincipal String username) {
        return workflowService.withTransitionOptions(documentService.upload(folderId, file, changeDescription, username));
    }

    @GetMapping("/folders/{folderId}/documents")
    public List<DocumentResponse> list(@PathVariable Long folderId) {
        return documentService.listInFolder(folderId);
    }

    @GetMapping("/documents/{id}")
    public DocumentResponse detail(@PathVariable Long id) {
        return workflowService.withTransitionOptions(documentService.detail(id));
    }

    @PatchMapping("/documents/{id}/tags")
    public DocumentResponse updateTags(@PathVariable Long id,
                                       @Valid @RequestBody UpdateDocumentTagsRequest request) {
        return documentService.updateTags(id, request.tags());
    }

    @PatchMapping("/documents/{id}/move")
    public DocumentResponse move(@PathVariable Long id,
                                 @Valid @RequestBody MoveDocumentRequest request) {
        return workflowService.withTransitionOptions(documentService.move(id, request.targetFolderId()));
    }

    @PostMapping("/documents/{id}/status")
    public DocumentResponse changeStatus(@PathVariable Long id,
                                         @Valid @RequestBody StatusChangeRequest request,
                                         @AuthenticationPrincipal String username) {
        return workflowService.withTransitionOptions(
                workflowService.requestStatusChange(id, request.to(), request.note(), username));
    }

    @GetMapping("/documents/{id}/versions")
    public List<DocumentVersionResponse> versions(@PathVariable Long id) {
        return documentService.versions(id);
    }

    @GetMapping("/documents/{id}/status-history")
    public List<DocumentStatusChangeResponse> statusHistory(@PathVariable Long id) {
        return statusHistoryService.history(id);
    }

    @GetMapping("/documents/{id}/diff")
    public DiffResponse diff(@PathVariable Long id,
                             @RequestParam(value = "from", required = false) Integer from,
                             @RequestParam(value = "to", required = false) Integer to) {
        return documentService.diff(id, from, to);
    }

    @GetMapping("/versions/{versionId}/download")
    public ResponseEntity<byte[]> download(@PathVariable Long versionId) {
        VersionDownload download = documentService.downloadVersion(versionId);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(download.documentName(), StandardCharsets.UTF_8)
                        .build()
                        .toString())
                .body(download.content());
    }

    @GetMapping("/versions/{versionId}/preview")
    public ResponseEntity<byte[]> preview(@PathVariable Long versionId) {
        VersionDownload download = documentService.downloadVersion(versionId);
        MediaType type = download.documentName().toLowerCase().endsWith(".pdf")
                ? MediaType.APPLICATION_PDF
                : download.documentName().toLowerCase().endsWith(".png") ? MediaType.IMAGE_PNG
                : MediaType.IMAGE_JPEG;
        return ResponseEntity.ok().contentType(type)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                        .filename(download.documentName(), StandardCharsets.UTF_8).build().toString())
                .body(download.content());
    }

    @DeleteMapping("/documents/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        documentService.delete(id);
    }
}
