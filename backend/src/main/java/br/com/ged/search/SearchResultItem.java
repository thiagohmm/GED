package br.com.ged.search;

import br.com.ged.document.DocumentKind;
import br.com.ged.document.DocumentStatus;

import java.time.Instant;

public record SearchResultItem(Long documentId, int versionNumber, String documentName, DocumentKind kind,
                                DocumentStatus status, String folderPath, String snippet, Instant uploadedAt) {
}
