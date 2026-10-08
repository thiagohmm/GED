package br.com.ged.document;

import java.util.List;

public record DocumentResponse(Long id, Long folderId, String name, DocumentKind kind, int currentVersion,
                                DocumentStatus status, List<String> tags, List<DocumentStatus> allowedTransitions) {

    public static DocumentResponse from(Document document) {
        return new DocumentResponse(
                document.getId(),
                document.getFolder().getId(),
                document.getName(),
                document.getKind(),
                document.getCurrentVersion(),
                document.getStatus(),
                document.getTags() == null ? List.of() : List.copyOf(document.getTags()),
                List.of());
    }

    public DocumentResponse withTransitions(List<DocumentStatus> transitions) {
        return new DocumentResponse(id, folderId, name, kind, currentVersion, status, tags,
                transitions == null ? List.of() : transitions);
    }
}
