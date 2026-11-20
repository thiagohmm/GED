package br.com.ged.user;

import br.com.ged.audit.AuditEventService;
import br.com.ged.config.AuthenticatedUser;
import br.com.ged.exception.BusinessException;
import br.com.ged.exception.ConflictException;
import br.com.ged.exception.NotFoundException;
import br.com.ged.office.Office;
import br.com.ged.office.OfficeRepository;
import br.com.ged.tenant.TenantScope;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final TenantScope tenantScope;
    private final AuditEventService auditEventService;
    private final OfficeRepository officeRepository;

    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder,
                       TenantScope tenantScope, AuditEventService auditEventService,
                       OfficeRepository officeRepository) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.tenantScope = tenantScope;
        this.auditEventService = auditEventService;
        this.officeRepository = officeRepository;
    }

    @Transactional(readOnly = true)
    public List<UserResponse> listAll() {
        Map<Long, String> officeNames = officeNameById();
        return tenantScope.currentOffice()
                .<List<UserResponse>>map(officeId -> userRepository.findByOfficeIdOrderByUsernameAsc(officeId)
                        .stream().map(user -> withOfficeName(user, officeNames)).toList())
                .orElseGet(() -> userRepository.findAll().stream()
                        .map(user -> withOfficeName(user, officeNames)).toList());
    }

    @Transactional(readOnly = true)
    public List<UserResponse> listActiveAttorneys() {
        Map<Long, String> officeNames = officeNameById();
        List<User> candidates = tenantScope.currentOffice()
                .<List<User>>map(userRepository::findByOfficeIdOrderByUsernameAsc)
                .orElseGet(userRepository::findAll);
        return candidates.stream()
                .filter(user -> user.getRole() == Role.ADVOGADO && user.isEnabled())
                .map(user -> withOfficeName(user, officeNames))
                .toList();
    }

    private UserResponse withOfficeName(User user, Map<Long, String> officeNames) {
        String name = user.getOfficeId() == null ? null : officeNames.get(user.getOfficeId());
        return UserResponse.from(user).withOfficeName(name);
    }

    private Map<Long, String> officeNameById() {
        return officeRepository.findAll().stream().collect(Collectors.toMap(
                Office::getId, Office::getName));
    }

    private String singleOfficeName(Long officeId) {
        if (officeId == null) {
            return null;
        }
        return officeRepository.findById(officeId).map(Office::getName).orElse(null);
    }

    @Transactional
    public UserResponse create(CreateUserRequest request) {
        String username = request.username().strip();
        rejectExistingUsername(username);
        AuthenticatedUser actor = tenantScope.currentPrincipal();
        if (actor == null) {
            throw new AccessDeniedException("Autenticacao obrigatoria.");
        }
        if (!actor.isSuperAdmin() && (request.role() == Role.SUPER_ADMIN || request.role() == Role.ADMIN)) {
            throw new AccessDeniedException("Apenas o SUPER_ADMIN pode criar usuarios ADMIN ou SUPER_ADMIN.");
        }
        Long officeId = request.role() == Role.SUPER_ADMIN ? null : resolveOffice(request.officeId());
        User user = new User(
                username,
                passwordEncoder.encode(request.password()),
                request.role(),
                true,
                officeId);
        User saved = userRepository.save(user);
        auditEventService.record("USER_CREATE", "USER", saved.getUsername(),
                AuditEventService.details("username", saved.getUsername(), "role", saved.getRole().name(),
                        "officeId", saved.getOfficeId()));
        return UserResponse.from(saved).withOfficeName(singleOfficeName(saved.getOfficeId()));
    }

    @Transactional
    public UserResponse update(Long id, UpdateUserRequest request) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Usuario nao encontrado."));
        tenantScope.requireAccessToUser(user);
        applyRoleProtections(user, request);
        boolean passwordChanged = false;
        if (request.password() != null) {
            if (request.password().isBlank()) {
                throw new BusinessException("A nova senha nao pode estar em branco.");
            }
            user.setPasswordHash(passwordEncoder.encode(request.password()));
            user.setTokenVersion(user.getTokenVersion() + 1);
            passwordChanged = true;
        }
        if (request.role() != null) {
            user.setRole(request.role());
        }
        if (request.enabled() != null) {
            user.setEnabled(request.enabled());
        }
        User saved = userRepository.save(user);
        auditEventService.record("USER_UPDATE", "USER", saved.getUsername(),
                AuditEventService.details("role", saved.getRole().name(), "enabled", saved.isEnabled(),
                        "passwordChanged", passwordChanged));
        return UserResponse.from(saved).withOfficeName(singleOfficeName(saved.getOfficeId()));
    }

    /**
     * Protecoes de papel:
     *  - SUPER_ADMIN so e alterado por outro SUPER_ADMIN; papel e status sao imutaveis;
     *  - nenhum usuario de escritorio pode ser promovido a SUPER_ADMIN;
     *  - nenhum usuario desativa a si mesmo nem altera o proprio papel;
     *  - ADMIN nao altera nem promove usuarios para ADMIN/SUPER_ADMIN (apenas SUPER_ADMIN).
     */
    private void applyRoleProtections(User target, UpdateUserRequest request) {
        AuthenticatedUser actor = tenantScope.currentPrincipal();
        if (actor == null) {
            throw new AccessDeniedException("Autenticacao obrigatoria.");
        }
        boolean self = actor.userId() != null && actor.userId().equals(target.getId());
        if (self && request.enabled() != null && !request.enabled()) {
            throw new BusinessException("Nao e possivel desativar o proprio usuario.");
        }
        if (self && request.role() != null && request.role() != target.getRole()) {
            throw new BusinessException("Nao e possivel alterar o proprio papel.");
        }
        if (target.getRole() == Role.SUPER_ADMIN) {
            if (!actor.isSuperAdmin()) {
                throw new AccessDeniedException("Apenas o SUPER_ADMIN pode alterar outro SUPER_ADMIN.");
            }
            if (request.enabled() != null && !request.enabled()) {
                throw new BusinessException("O SUPER_ADMIN nao pode ser desativado.");
            }
            if (request.role() != null && request.role() != Role.SUPER_ADMIN) {
                throw new BusinessException("O papel SUPER_ADMIN nao pode ser alterado.");
            }
        } else if (request.role() == Role.SUPER_ADMIN) {
            throw new BusinessException("Usuarios vinculados a escritorio nao podem receber o papel SUPER_ADMIN.");
        }
        if (actor.role() != Role.SUPER_ADMIN
                && (target.getRole() == Role.ADMIN || request.role() == Role.ADMIN)) {
            throw new AccessDeniedException("Apenas o SUPER_ADMIN pode atribuir ou alterar o papel ADMIN.");
        }
    }

    private Long resolveOffice(Long requestedOfficeId) {
        Long officeId = tenantScope.currentOffice()
                .map(currentOfficeId -> {
                    if (requestedOfficeId != null && !requestedOfficeId.equals(currentOfficeId)) {
                        throw new ConflictException("Usuarios so podem ser criados no proprio escritorio.");
                    }
                    return currentOfficeId;
                })
                .orElseGet(() -> {
                    if (requestedOfficeId == null) {
                        throw new BusinessException("Informe o escritorio do usuario.");
                    }
                    return requestedOfficeId;
                });
        Office office = officeRepository.findById(officeId)
                .orElseThrow(() -> new NotFoundException("Escritorio nao encontrado."));
        if (!office.isEnabled()) {
            throw new BusinessException("Escritorio desativado nao permite criar novos usuarios.");
        }
        return officeId;
    }

    @Transactional
    public void changeOwnPassword(String username, ChangePasswordRequest request) {
        User user = userRepository.findByUsernameIgnoreCase(username)
                .orElseThrow(() -> new NotFoundException("Usuario nao encontrado."));
        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            throw new BusinessException("A senha atual esta incorreta.");
        }
        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        user.setTokenVersion(user.getTokenVersion() + 1);
        auditEventService.record("USER_PASSWORD_CHANGE", "USER", user.getUsername(), null);
    }

    private void rejectExistingUsername(String username) {
        if (userRepository.existsByUsernameIgnoreCase(username)) {
            throw new ConflictException("Nome de usuario ja existe.");
        }
    }
}
