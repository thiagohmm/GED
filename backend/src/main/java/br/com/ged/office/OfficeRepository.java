package br.com.ged.office;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface OfficeRepository extends JpaRepository<Office, Long> {

    Optional<Office> findByNameIgnoreCase(String name);

    List<Office> findAllByOrderByNameAsc();
}
