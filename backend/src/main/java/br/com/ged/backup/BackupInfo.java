package br.com.ged.backup;

public record BackupInfo(String name, String createdAt, long databaseBytes, long versions, long documents) {
}
