package br.com.ged.document;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface DocumentRepository extends JpaRepository<Document, Long> {

    Optional<Document> findByFolderIdAndNameIgnoreCase(Long folderId, String name);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from Document d where d.folder.id = :folderId and lower(d.name) = lower(:name)")
    Optional<Document> findByFolderIdAndNameIgnoreCaseForUpdate(@Param("folderId") Long folderId,
                                                                @Param("name") String name);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from Document d where d.id = :id")
    Optional<Document> findByIdForUpdate(@Param("id") Long id);

    @Query("select d from Document d where d.folder.id = :folderId order by lower(d.name) asc")
    List<Document> findByFolderIdSortedByName(@Param("folderId") Long folderId);

    boolean existsByFolderId(Long folderId);
}
