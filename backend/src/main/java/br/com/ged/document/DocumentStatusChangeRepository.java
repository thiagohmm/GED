package br.com.ged.document;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DocumentStatusChangeRepository extends JpaRepository<DocumentStatusChange, Long> {

    List<DocumentStatusChange> findByDocumentIdOrderByChangedAtDesc(Long documentId);
}
