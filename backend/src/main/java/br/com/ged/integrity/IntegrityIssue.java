package br.com.ged.integrity;

public record IntegrityIssue(Long documentId, String documentName, int version,
                             String problem, String filePath, String expectedHash, String actualHash) {
}
