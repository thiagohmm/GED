package br.com.ged.config;

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

@Component
@Order(2)
public class DataSeeder implements ApplicationRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final String adminUsername;
    private final String adminPassword;

    public DataSeeder(UserRepository userRepository,
                      PasswordEncoder passwordEncoder,
                      @Value("${ged.seed.admin-username:admin}") String adminUsername,
                      @Value("${ged.seed.admin-password:admin123}") String adminPassword) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.adminUsername = adminUsername;
        this.adminPassword = adminPassword;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (userRepository.count() == 0) {
            userRepository.save(new User(adminUsername, passwordEncoder.encode(adminPassword), Role.ADMIN, true));
        }
    }
}
