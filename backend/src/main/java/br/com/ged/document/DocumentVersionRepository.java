package br.com.ged.document;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DocumentVersionRepository extends JpaRepository<DocumentVersion, Long> {

    List<DocumentVersion> findByDocumentOrderByVersionNumberDesc(Document document);

    Optional<DocumentVersion> findTopByDocumentOrderByVersionNumberDesc(Document document);

    Optional<DocumentVersion> findByDocumentAndVersionNumber(Document document, int versionNumber);
}
