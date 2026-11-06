package br.com.ged.auth;

import br.com.ged.audit.AuditEventService;
import br.com.ged.config.JwtService;
import br.com.ged.exception.InvalidCredentialsException;
import br.com.ged.user.User;
import br.com.ged.user.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuditEventService auditEventService;

    public AuthService(UserRepository userRepository,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService,
                       AuditEventService auditEventService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.auditEventService = auditEventService;
    }

    public LoginResponse login(LoginRequest request) {
        java.util.Optional<User> candidate = userRepository.findByUsernameIgnoreCase(request.username());
        boolean valid = candidate.isPresent()
                && candidate.get().isEnabled()
                && passwordEncoder.matches(request.password(), candidate.get().getPasswordHash());
        if (!valid) {
            auditEventService.record("AUTH_FAIL", "USER", request.username(),
                    AuditEventService.details("username", request.username()));
            throw new InvalidCredentialsException();
        }
        User user = candidate.get();
        auditEventService.record("USER_LOGIN", "USER", user.getUsername(), null);
        return new LoginResponse(jwtService.issueToken(user), user.getUsername(), user.getRole(), user.getId(), user.getOfficeId());
    }
}
