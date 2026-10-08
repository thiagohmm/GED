package br.com.ged.auth;

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

    public AuthService(UserRepository userRepository,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    public LoginResponse login(LoginRequest request) {
        User user = userRepository.findByUsernameIgnoreCase(request.username())
                .filter(User::isEnabled)
                .filter(foundUser -> passwordEncoder.matches(request.password(), foundUser.getPasswordHash()))
                .orElseThrow(InvalidCredentialsException::new);
        return new LoginResponse(jwtService.issueToken(user), user.getUsername(), user.getRole());
    }
}
