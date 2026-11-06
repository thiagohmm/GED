package br.com.ged.backup;

import java.time.Instant;
import java.util.List;

public record BackupManifest(String createdAt, long users, long offices, long documents, long versions,
                             List<BackupFileEntry> files) {

    public record BackupFileEntry(String relPath, String sha256) {
    }

    public static BackupManifest of(Instant createdAt, long users, long offices, long documents, long versions,
                                    List<BackupFileEntry> files) {
        return new BackupManifest(createdAt.toString(), users, offices, documents, versions, files);
    }
}
