package br.com.ged.auth;

import br.com.ged.audit.AuditEventService;
import br.com.ged.config.JwtService;
import br.com.ged.exception.InvalidCredentialsException;
import br.com.ged.office.Office;
import br.com.ged.office.OfficeRepository;
import br.com.ged.user.Role;
import br.com.ged.user.User;
import br.com.ged.user.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final OfficeRepository officeRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuditEventService auditEventService;
    private final String dummyPasswordHash;

    public AuthService(UserRepository userRepository,
                       OfficeRepository officeRepository,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService,
                       AuditEventService auditEventService) {
        this.userRepository = userRepository;
        this.officeRepository = officeRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.auditEventService = auditEventService;
        this.dummyPasswordHash = passwordEncoder.encode("ged-dummy-password-for-constant-time-check");
    }

    public LoginResponse login(LoginRequest request) {
        Optional<User> candidate = userRepository.findByUsernameIgnoreCase(request.username());
        String hashToVerify = candidate.map(User::getPasswordHash).orElse(dummyPasswordHash);
        boolean passwordMatches = passwordEncoder.matches(request.password(), hashToVerify);
        boolean valid = candidate.isPresent()
                && passwordMatches
                && candidate.get().isEnabled()
                && isOfficeActive(candidate.get());
        if (!valid) {
            auditEventService.record("AUTH_FAIL", "USER", request.username(),
                    AuditEventService.details("username", request.username()));
            throw new InvalidCredentialsException();
        }
        User user = candidate.get();
        auditEventService.record("USER_LOGIN", "USER", user.getUsername(), null);
        return new LoginResponse(jwtService.issueToken(user), user.getUsername(), user.getRole(), user.getId(), user.getOfficeId());
    }

    private boolean isOfficeActive(User user) {
        if (user.getRole() == Role.SUPER_ADMIN) {
            return true;
        }
        if (user.getOfficeId() == null) {
            return false;
        }
        return officeRepository.findById(user.getOfficeId())
                .map(Office::isEnabled)
                .orElse(false);
    }
}
