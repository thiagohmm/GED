package br.com.ged.document;

import br.com.ged.folder.Folder;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "documents", uniqueConstraints = {
        @UniqueConstraint(name = "uk_documents_folder_name", columnNames = {"folder_id", "name"})
})
@Getter
@Setter
@NoArgsConstructor
public class Document {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "folder_id", nullable = false)
    private Folder folder;

    @Column(nullable = false, length = 255)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private DocumentKind kind;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, columnDefinition = "varchar(30) default 'EDITANDO'")
    private DocumentStatus status;

    @Column(name = "current_version", nullable = false)
    private int currentVersion;

    @Column(name = "created_by", length = 60)
    private String createdBy;

    @OneToMany(mappedBy = "document", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<DocumentVersion> versions = new ArrayList<>();

    @OneToMany(mappedBy = "document", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<DocumentStatusChange> statusChanges = new ArrayList<>();

    @ElementCollection
    @CollectionTable(name = "document_tags", joinColumns = @JoinColumn(name = "document_id"))
    @Column(name = "tag", nullable = false, length = 40)
    @OrderColumn(name = "tag_order")
    private List<String> tags = new ArrayList<>();

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public Document(Folder folder, String name, DocumentKind kind, String createdBy) {
        this.folder = folder;
        this.name = name;
        this.kind = kind;
        this.createdBy = createdBy;
        this.status = DocumentStatus.EDITANDO;
        this.currentVersion = 1;
    }
}
