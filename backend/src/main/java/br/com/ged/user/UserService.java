package br.com.ged.user;

import br.com.ged.audit.AuditEventService;
import br.com.ged.exception.ConflictException;
import br.com.ged.exception.NotFoundException;
import br.com.ged.exception.BusinessException;
import br.com.ged.config.AuthenticatedUser;
import br.com.ged.office.OfficeRepository;
import br.com.ged.tenant.TenantScope;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
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
                        .stream().map(this::withOfficeName).toList())
                .orElseGet(() -> userRepository.findAll().stream().map(this::withOfficeName).toList());
    }

    private UserResponse withOfficeName(User user) {
        return UserResponse.from(user).withOfficeName(officeName(user.getOfficeId()));
    }

    private Map<Long, String> officeNameById() {
        return officeRepository.findAll().stream().collect(Collectors.toMap(
                br.com.ged.office.Office::getId, br.com.ged.office.Office::getName));
    }

    private String officeName(Long officeId) {
        if (officeId == null) {
            return null;
        }
        return officeNameById().get(officeId);
    }

    @Transactional
    public UserResponse create(CreateUserRequest request) {
        rejectExistingUsername(request.username());
        AuthenticatedUser actor = tenantScope.currentPrincipal();
        if (actor != null && !actor.isSuperAdmin() && request.role() == Role.SUPER_ADMIN) {
            throw new AccessDeniedException("Apenas o SUPER_ADMIN pode criar outro SUPER_ADMIN.");
        }
        Long officeId = resolveOffice(request.officeId());
        User user = new User(
                request.username(),
                passwordEncoder.encode(request.password()),
                request.role(),
                true,
                officeId);
        User saved = userRepository.save(user);
        auditEventService.record("USER_CREATE", "USER", saved.getUsername(),
                AuditEventService.details("username", saved.getUsername(), "role", saved.getRole().name(),
                        "officeId", saved.getOfficeId()));
        return UserResponse.from(saved).withOfficeName(officeName(saved.getOfficeId()));
    }

    @Transactional
    public UserResponse update(Long id, UpdateUserRequest request) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Usuario nao encontrado."));
        tenantScope.requireAccessToUser(user);
        applyRoleProtections(user, request);
        if (request.password() != null) {
            user.setPasswordHash(passwordEncoder.encode(request.password()));
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
                        "passwordChanged", request.password() != null));
        return UserResponse.from(saved).withOfficeName(officeName(saved.getOfficeId()));
    }

    /**
     * Protecoes de papel:
     *  - SUPER_ADMIN so e alterado por outro SUPER_ADMIN; papel e status sao imutaveis;
     *  - nenhum usuario desativa a si mesmo nem altera o proprio papel;
     *  - ADMIN nao altera outro ADMIN (apenas SUPER_ADMIN).
     */
    private void applyRoleProtections(User target, UpdateUserRequest request) {
        AuthenticatedUser actor = tenantScope.currentPrincipal();
        if (actor == null) {
            return;
        }
        boolean self = actor.userId() != null && actor.userId().equals(target.getId());
        if (self && request.enabled() != null && !request.enabled()) {
            throw new BusinessException("Nao e possivel desativar o proprio usuario.");
        }
        if (self && request.role() != null && request.role() != target.getRole()) {
            throw new BusinessException("Nao e possivel alterar o proprio papel.");
        }
        if (target.getRole() == Role.SUPER_ADMIN) {
            if (request.enabled() != null && !request.enabled()) {
                throw new BusinessException("O SUPER_ADMIN nao pode ser desativado.");
            }
            if (request.role() != null && request.role() != Role.SUPER_ADMIN) {
                throw new BusinessException("O papel SUPER_ADMIN nao pode ser alterado.");
            }
        }
        if (actor.role() != Role.SUPER_ADMIN && target.getRole() == Role.ADMIN) {
            throw new AccessDeniedException("Apenas o SUPER_ADMIN pode alterar outro ADMIN.");
        }
    }

    /**
     * Escritorio do usuario criado: o do logado (ou, para SUPER_ADMIN, o informado no request).
     */
    private Long resolveOffice(Long requestedOfficeId) {
        return tenantScope.currentOffice()
                .map(officeId -> {
                    if (requestedOfficeId != null && !requestedOfficeId.equals(officeId)) {
                        throw new ConflictException("Usuarios so podem ser criados no proprio escritorio.");
                    }
                    return officeId;
                })
                .orElseGet(() -> {
                    if (requestedOfficeId == null) {
                        throw new BusinessException("Informe o escritorio do usuario.");
                    }
                    return requestedOfficeId;
                });
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
