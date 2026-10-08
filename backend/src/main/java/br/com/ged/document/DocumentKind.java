package br.com.ged.document;

import java.util.Optional;

public enum DocumentKind {
    WORD,
    EXCEL,
    PDF,
    IMAGE;

    public static Optional<DocumentKind> fromFileName(String fileName) {
        int extensionSeparatorIndex = fileName.lastIndexOf('.');
        if (extensionSeparatorIndex == -1 || extensionSeparatorIndex == fileName.length() - 1) {
            return Optional.empty();
        }
        String extension = fileName.substring(extensionSeparatorIndex + 1).toLowerCase();
        return switch (extension) {
            case "docx" -> Optional.of(WORD);
            case "xlsx" -> Optional.of(EXCEL);
            case "pdf" -> Optional.of(PDF);
            case "png", "jpg", "jpeg" -> Optional.of(IMAGE);
            default -> Optional.empty();
        };
    }

    public boolean isVersionable() {
        return this == WORD || this == EXCEL;
    }
}
