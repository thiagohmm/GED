package br.com.ged.document;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface DocumentRepository extends JpaRepository<Document, Long> {

    Optional<Document> findByFolderIdAndNameIgnoreCase(Long folderId, String name);

    @Query("select d from Document d where d.folder.id = :folderId order by lower(d.name) asc")
    List<Document> findByFolderIdSortedByName(@Param("folderId") Long folderId);

    boolean existsByFolderId(Long folderId);
}
