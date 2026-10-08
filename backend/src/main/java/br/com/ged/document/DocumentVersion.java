package br.com.ged.document;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

@Entity
@Table(name = "document_versions", uniqueConstraints = {
        @UniqueConstraint(name = "uk_document_versions_document_number", columnNames = {"document_id", "version_number"})
})
@Getter
@Setter
@NoArgsConstructor
public class DocumentVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "document_id", nullable = false)
    private Document document;

    @Column(name = "version_number", nullable = false)
    private int versionNumber;

    @Column(name = "file_path", nullable = false, length = 500)
    private String filePath;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "varchar(30) default 'EDITANDO'")
    private DocumentStatus status;

    @Column(name = "sha256", length = 64)
    private String sha256;

    @Column(name = "change_description", length = 500)
    private String changeDescription;

    @Column(columnDefinition = "text")
    private String extractedText;

    @Column(columnDefinition = "text")
    private String diffJson;

    @Column(name = "uploaded_by", length = 60)
    private String uploadedBy;

    @CreationTimestamp
    @Column(name = "uploaded_at", nullable = false, updatable = false)
    private Instant uploadedAt;

    public DocumentVersion(Document document, int versionNumber, String filePath, long sizeBytes,
                           String sha256, String changeDescription, String uploadedBy) {
        this.document = document;
        this.versionNumber = versionNumber;
        this.filePath = filePath;
        this.sizeBytes = sizeBytes;
        this.sha256 = sha256;
        this.changeDescription = changeDescription;
        this.uploadedBy = uploadedBy;
    }
}
