package br.com.ged.office;

import br.com.ged.audit.AuditEventService;
import br.com.ged.exception.BusinessException;
import br.com.ged.exception.ConflictException;
import br.com.ged.exception.NotFoundException;
import br.com.ged.project.ProjectRepository;
import br.com.ged.user.Role;
import br.com.ged.user.User;
import br.com.ged.user.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class OfficeService {

    private final OfficeRepository officeRepository;
    private final UserRepository userRepository;
    private final ProjectRepository projectRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditEventService auditEventService;

    public OfficeService(OfficeRepository officeRepository,
                         UserRepository userRepository,
                         ProjectRepository projectRepository,
                         PasswordEncoder passwordEncoder,
                         AuditEventService auditEventService) {
        this.officeRepository = officeRepository;
        this.userRepository = userRepository;
        this.projectRepository = projectRepository;
        this.passwordEncoder = passwordEncoder;
        this.auditEventService = auditEventService;
    }

    @Transactional(readOnly = true)
    public List<OfficeResponse> list() {
        return officeRepository.findAllByOrderByNameAsc().stream().map(OfficeResponse::from).toList();
    }

    @Transactional
    public OfficeResponse create(CreateOfficeRequest request) {
        String name = request.name().strip();
        if (officeRepository.findByNameIgnoreCase(name).isPresent()) {
            throw new ConflictException("Ja existe um escritorio com este nome.");
        }
        Office saved = officeRepository.save(new Office(name));
        auditEventService.record("OFFICE_CREATE", "OFFICE", String.valueOf(saved.getId()),
                AuditEventService.details("name", saved.getName()));
        return OfficeResponse.from(saved);
    }

    @Transactional
    public OfficeResponse update(Long id, UpdateOfficeRequest request) {
        Office office = findById(id);
        if (request.name() != null && !request.name().isBlank()) {
            String name = request.name().strip();
            officeRepository.findByNameIgnoreCase(name)
                    .filter(existing -> !existing.getId().equals(id))
                    .ifPresent(existing -> {
                        throw new ConflictException("Ja existe um escritorio com este nome.");
                    });
            office.setName(name);
        }
        if (request.enabled() != null) {
            office.setEnabled(request.enabled());
        }
        Office saved = officeRepository.save(office);
        auditEventService.record("OFFICE_UPDATE", "OFFICE", String.valueOf(saved.getId()),
                AuditEventService.details("name", saved.getName(), "enabled", saved.isEnabled()));
        return OfficeResponse.from(saved);
    }

    @Transactional
    public void delete(Long id) {
        Office office = findById(id);
        if (userRepository.countByOfficeId(office.getId()) > 0
                || projectRepository.countByOfficeId(office.getId()) > 0) {
            throw new BusinessException(
                    "Escritorio com usuarios ou projetos nao pode ser removido. Desative o escritorio.");
        }
        auditEventService.record("OFFICE_DELETE", "OFFICE", String.valueOf(office.getId()),
                AuditEventService.details("name", office.getName()));
        officeRepository.delete(office);
    }

    @Transactional
    public br.com.ged.user.UserResponse createAdmin(Long officeId, CreateOfficeAdminRequest request) {
        Office office = findById(officeId);
        String username = request.username().strip();
        if (userRepository.existsByUsernameIgnoreCase(username)) {
            throw new ConflictException("Ja existe um usuario com este nome.");
        }
        User admin = new User(username, passwordEncoder.encode(request.password()), Role.ADMIN, true, office.getId());
        User saved = userRepository.save(admin);
        auditEventService.record("OFFICE_ADMIN_CREATE", "USER", saved.getUsername(),
                AuditEventService.details("officeId", office.getId()));
        return br.com.ged.user.UserResponse.from(saved);
    }

    private Office findById(Long id) {
        return officeRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Escritorio nao encontrado."));
    }
}
