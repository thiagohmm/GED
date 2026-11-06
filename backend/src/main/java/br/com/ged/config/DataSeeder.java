package br.com.ged.config;

import br.com.ged.office.Office;
import br.com.ged.office.OfficeRepository;
import br.com.ged.user.Role;
import br.com.ged.user.User;
import br.com.ged.user.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Seed inicial:
 *  - dev (profile dev / ged.seed.enabled=true): cria o escritorio de desenvolvimento e o
 *    admin local quando nao ha usuarios. Senha fraca permitida APENAS aqui.
 *  - prod: cria um SUPER_ADMIN global (sem escritorio) quando nao existe; sem as variaveis
 *    GED_SUPER_ADMIN_USERNAME/PASSWORD, a subida falha com mensagem explicita.
 */
@Component
@Order(2)
public class DataSeeder implements ApplicationRunner {

    private final UserRepository userRepository;
    private final OfficeRepository officeRepository;
    private final PasswordEncoder passwordEncoder;
    private final boolean seedEnabled;
    private final String devAdminUsername;
    private final String devAdminPassword;
    private final String devOfficeName;
    private final String superAdminUsername;
    private final String superAdminPassword;

    public DataSeeder(UserRepository userRepository,
                      OfficeRepository officeRepository,
                      PasswordEncoder passwordEncoder,
                      @Value("${ged.seed.enabled:false}") boolean seedEnabled,
                      @Value("${ged.seed.admin-username:}") String devAdminUsername,
                      @Value("${ged.seed.admin-password:}") String devAdminPassword,
                      @Value("${ged.seed.office-name:Escritorio Dev}") String devOfficeName,
                      @Value("${ged.seed.super-admin-username:}") String superAdminUsername,
                      @Value("${ged.seed.super-admin-password:}") String superAdminPassword) {
        this.userRepository = userRepository;
        this.officeRepository = officeRepository;
        this.passwordEncoder = passwordEncoder;
        this.seedEnabled = seedEnabled;
        this.devAdminUsername = devAdminUsername;
        this.devAdminPassword = devAdminPassword;
        this.devOfficeName = devOfficeName;
        this.superAdminUsername = superAdminUsername;
        this.superAdminPassword = superAdminPassword;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (seedEnabled) {
            seedDev();
            return;
        }
        if (!userRepository.existsByRole(Role.SUPER_ADMIN)) {
            seedSuperAdmin();
        }
    }

    private void seedDev() {
        if (userRepository.count() > 0) {
            return;
        }
        if (devAdminUsername.isBlank() || devAdminPassword.isBlank()) {
            throw new IllegalStateException(
                    "Seed de desenvolvimento requer ged.seed.admin-username/admin-password (definidos no profile dev).");
        }
        Office office = officeRepository.save(new Office(devOfficeName));
        userRepository.save(new User(devAdminUsername, passwordEncoder.encode(devAdminPassword),
                Role.ADMIN, true, office.getId()));
        // SUPER_ADMIN de desenvolvimento para exercitar a gestao de escritorios; nao existe em producao.
        userRepository.save(new User("root", passwordEncoder.encode(devAdminPassword),
                Role.SUPER_ADMIN, true, null));
    }

    private void seedSuperAdmin() {
        if (superAdminUsername.isBlank() || superAdminPassword.isBlank()) {
            throw new IllegalStateException(
                    "Nao ha SUPER_ADMIN no banco e GED_SUPER_ADMIN_USERNAME/GED_SUPER_ADMIN_PASSWORD nao foram definidas. "
                            + "Defina as duas variaveis de ambiente para criar o administrador global na primeira subida.");
        }
        userRepository.save(new User(superAdminUsername, passwordEncoder.encode(superAdminPassword),
                Role.SUPER_ADMIN, true, null));
    }
}
