package br.com.ged.project;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProjectRepository extends JpaRepository<Project, Long> {

    Optional<Project> findByNameIgnoreCase(String name);

    Optional<Project> findByOfficeIdAndNameIgnoreCase(Long officeId, String name);

    List<Project> findAllByOrderByNameAsc();

    List<Project> findByOfficeIdOrderByNameAsc(Long officeId);

    long countByOfficeId(Long officeId);
}
