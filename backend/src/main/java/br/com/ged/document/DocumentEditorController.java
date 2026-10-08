package br.com.ged.document;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.Map;

@RestController
@RequestMapping("/api/editor")
@RequiredArgsConstructor
public class DocumentEditorController {

    private final DocumentEditorService editorService;

    @GetMapping("/documents/{id}/config")
    public EditorConfigResponse config(@PathVariable Long id,
                                       @RequestParam(defaultValue = "view") String mode,
                                       @AuthenticationPrincipal String username) {
        return editorService.config(id, username, mode);
    }

    @GetMapping("/versions/{versionId}/file")
    public ResponseEntity<byte[]> editorFile(@PathVariable Long versionId, @RequestParam String token) {
        VersionDownload file = editorService.editorFile(versionId, token);
        return ResponseEntity.ok().contentType(mediaType(file.documentName()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                        .filename(file.documentName(), StandardCharsets.UTF_8).build().toString())
                .body(file.content());
    }

    @PostMapping("/callback/{documentId}")
    public Map<String, Integer> callback(@PathVariable Long documentId, @RequestParam String token,
                                         @RequestBody Map<String, Object> body) {
        return editorService.callback(documentId, token, body);
    }

    private MediaType mediaType(String name) {
        String lower = name.toLowerCase();
        if (lower.endsWith(".pdf")) return MediaType.APPLICATION_PDF;
        if (lower.endsWith(".docx")) return MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        if (lower.endsWith(".png")) return MediaType.IMAGE_PNG;
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return MediaType.IMAGE_JPEG;
        return MediaType.APPLICATION_OCTET_STREAM;
    }
}
