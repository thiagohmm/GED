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
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

@Entity
@Table(name = "document_status_changes")
@Getter
@NoArgsConstructor
public class DocumentStatusChange {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "document_id", nullable = false)
    private Document document;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", nullable = false, length = 30)
    private DocumentStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, length = 30)
    private DocumentStatus toStatus;

    @Column(columnDefinition = "text")
    private String note;

    @Column(name = "changed_by", length = 60)
    private String changedBy;

    @CreationTimestamp
    @Column(name = "changed_at", nullable = false, updatable = false)
    private Instant changedAt;

    public DocumentStatusChange(Document document, DocumentStatus fromStatus, DocumentStatus toStatus,
                                String note, String changedBy) {
        this.document = document;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.note = note;
        this.changedBy = changedBy;
    }
}
