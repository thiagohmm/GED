package br.com.ged.folder;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface FolderRepository extends JpaRepository<Folder, Long> {

    Optional<Folder> findByParentAndNameIgnoreCase(Folder parent, String name);

    Optional<Folder> findByParentIsNullAndNameIgnoreCase(String name);

    @Query("select f from Folder f where f.parent is null and lower(f.name) = lower(:name) and f.project.officeId = :officeId")
    Optional<Folder> findRootByOfficeIdAndNameIgnoreCase(@Param("officeId") Long officeId,
                                                         @Param("name") String name);

    boolean existsByParentId(Long parentId);

    List<Folder> findByParentId(Long parentId);
}
