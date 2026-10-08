package br.com.ged.document;

public record StoredFile(String relativePath, long sizeBytes, String sha256) {
}
