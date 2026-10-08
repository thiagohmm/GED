package br.com.ged.user;

import br.com.ged.exception.ConflictException;
import br.com.ged.exception.NotFoundException;
import br.com.ged.exception.BusinessException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional(readOnly = true)
    public List<UserResponse> listAll() {
        return userRepository.findAll().stream()
                .map(UserResponse::from)
                .toList();
    }

    @Transactional
    public UserResponse create(CreateUserRequest request) {
        rejectExistingUsername(request.username());
        User user = new User(
                request.username(),
                passwordEncoder.encode(request.password()),
                request.role(),
                true);
        return UserResponse.from(userRepository.save(user));
    }

    @Transactional
    public UserResponse update(Long id, UpdateUserRequest request) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Usuario nao encontrado."));
        if (request.password() != null) {
            user.setPasswordHash(passwordEncoder.encode(request.password()));
        }
        if (request.role() != null) {
            user.setRole(request.role());
        }
        if (request.enabled() != null) {
            user.setEnabled(request.enabled());
        }
        return UserResponse.from(userRepository.save(user));
    }

    @Transactional
    public void changeOwnPassword(String username, ChangePasswordRequest request) {
        User user = userRepository.findByUsernameIgnoreCase(username)
                .orElseThrow(() -> new NotFoundException("Usuario nao encontrado."));
        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            throw new BusinessException("A senha atual esta incorreta.");
        }
        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
    }

    private void rejectExistingUsername(String username) {
        if (userRepository.existsByUsernameIgnoreCase(username)) {
            throw new ConflictException("Nome de usuario ja existe.");
        }
    }
}
