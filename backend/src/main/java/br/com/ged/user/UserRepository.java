package br.com.ged.user;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.List;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByUsernameIgnoreCase(String username);

    boolean existsByUsernameIgnoreCase(String username);

    List<User> findByRoleAndEnabledTrueOrderByUsernameAsc(Role role);

    List<User> findByOfficeIdOrderByUsernameAsc(Long officeId);

    List<User> findByOfficeIdAndRoleAndEnabledTrueOrderByUsernameAsc(Long officeId, Role role);

    boolean existsByRole(Role role);

    long countByOfficeId(Long officeId);
}
