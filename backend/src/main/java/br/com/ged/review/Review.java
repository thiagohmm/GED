package br.com.ged.review;

import br.com.ged.document.Document;
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
@Table(name = "reviews", uniqueConstraints = {
        @UniqueConstraint(name = "uk_reviews_document_round", columnNames = {"document_id", "round"})
})
@Getter
@Setter
@NoArgsConstructor
public class Review {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "document_id", nullable = false)
    private Document document;

    @Column(nullable = false)
    private int round;

    @Column(name = "from_version", nullable = false)
    private int fromVersion;

    @Column(name = "to_version", nullable = false)
    private int toVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 25)
    private ReviewVerdict verdict;

    @Column(name = "created_by", length = 60)
    private String createdBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "decided_by", length = 60)
    private String decidedBy;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(columnDefinition = "text")
    private String summary;

    public Review(Document document, int round, int fromVersion, int toVersion, String createdBy) {
        this.document = document;
        this.round = round;
        this.fromVersion = fromVersion;
        this.toVersion = toVersion;
        this.createdBy = createdBy;
        this.verdict = ReviewVerdict.PENDENTE;
    }
}
