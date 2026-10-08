package br.com.ged.document;

import java.time.Instant;

public record DocumentVersionResponse(Long id, int versionNumber, long sizeBytes, String sha256,
                                       String changeDescription, DocumentStatus status, String uploadedBy,
                                       Instant uploadedAt, boolean hasDiff) {

    public static DocumentVersionResponse from(DocumentVersion version) {
        return new DocumentVersionResponse(
                version.getId(),
                version.getVersionNumber(),
                version.getSizeBytes(),
                version.getSha256(),
                version.getChangeDescription(),
                version.getStatus() == null ? DocumentStatus.EDITANDO : version.getStatus(),
                version.getUploadedBy(),
                version.getUploadedAt(),
                version.getDiffJson() != null);
    }
}
