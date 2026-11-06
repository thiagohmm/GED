package br.com.ged.document;

import br.com.ged.audit.AuditEventService;
import br.com.ged.exception.NotFoundException;
import br.com.ged.tenant.TenantScope;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class DocumentStatusHistoryService {

    private final DocumentRepository documentRepository;
    private final DocumentStatusChangeRepository statusChangeRepository;
    private final DocumentService documentService;
    private final TenantScope tenantScope;
    private final AuditEventService auditEventService;

    @Transactional
    public DocumentResponse changeStatus(Document document, DocumentStatus toStatus, String note, String changedBy) {
        DocumentStatus fromStatus = document.getStatus();
        DocumentStatusChange change = statusChangeRepository.save(new DocumentStatusChange(document, fromStatus, toStatus,
                blankToNull(note), changedBy));
        document.getStatusChanges().add(change);
        auditEventService.record("STATUS_CHANGE", "DOCUMENT", String.valueOf(document.getId()),
                AuditEventService.details("from", fromStatus.name(), "to", toStatus.name(), "note", blankToNull(note)));
        return documentService.updateStatus(document, toStatus);
    }

    @Transactional(readOnly = true)
    public List<DocumentStatusChangeResponse> history(Long documentId) {
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new NotFoundException("Documento nao encontrado."));
        tenantScope.requireAccessToDocument(document);
        return statusChangeRepository.findByDocumentIdOrderByChangedAtDesc(documentId).stream()
                .map(DocumentStatusChangeResponse::from)
                .toList();
    }

    private String blankToNull(String note) {
        return note == null || note.isBlank() ? null : note.strip();
    }
}
