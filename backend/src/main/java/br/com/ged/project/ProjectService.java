package br.com.ged.project;

import br.com.ged.exception.BusinessException;
import br.com.ged.exception.ConflictException;
import br.com.ged.exception.NotFoundException;
import br.com.ged.office.Office;
import br.com.ged.office.OfficeRepository;
import br.com.ged.tenant.TenantScope;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class ProjectService {

    private final ProjectRepository projectRepository;
    private final OfficeRepository officeRepository;
    private final TenantScope tenantScope;

    @Transactional(readOnly = true)
    public List<ProjectResponse> list() {
        Optional<Long> office = tenantScope.currentOffice();
        List<Project> projects = office
                .<List<Project>>map(projectRepository::findByOfficeIdOrderByNameAsc)
                .orElseGet(projectRepository::findAllByOrderByNameAsc);
        return projects.stream().map(ProjectResponse::from).toList();
    }

    @Transactional
    public ProjectResponse create(ProjectRequest request) {
        String name = request.name().strip();
        Long officeId = resolveOffice(request.officeId());
        if (projectRepository.findByOfficeIdAndNameIgnoreCase(officeId, name).isPresent()) {
            throw new ConflictException("Ja existe um projeto com este nome neste escritorio.");
        }
        return ProjectResponse.from(projectRepository.save(new Project(name, officeId)));
    }

    private Long resolveOffice(Long requestedOfficeId) {
        Optional<Long> current = tenantScope.currentOffice();
        if (current.isPresent()) {
            if (requestedOfficeId != null && !requestedOfficeId.equals(current.get())) {
                throw new ConflictException("Projetos so podem ser criados no proprio escritorio.");
            }
            requireActiveOffice(current.get());
            return current.get();
        }
        if (requestedOfficeId == null) {
            throw new BusinessException("Informe o escritorio do projeto.");
        }
        requireActiveOffice(requestedOfficeId);
        return requestedOfficeId;
    }

    private void requireActiveOffice(Long officeId) {
        Office office = officeRepository.findById(officeId)
                .orElseThrow(() -> new NotFoundException("Escritorio nao encontrado."));
        if (!office.isEnabled()) {
            throw new BusinessException("Escritorio desativado nao permite novas operacoes.");
        }
    }
}
