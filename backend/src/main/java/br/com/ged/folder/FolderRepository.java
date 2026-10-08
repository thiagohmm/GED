package br.com.ged.folder;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface FolderRepository extends JpaRepository<Folder, Long> {

    Optional<Folder> findByParentAndNameIgnoreCase(Folder parent, String name);

    Optional<Folder> findByParentIsNullAndNameIgnoreCase(String name);

    boolean existsByParentId(Long parentId);

    List<Folder> findByParentId(Long parentId);
}
