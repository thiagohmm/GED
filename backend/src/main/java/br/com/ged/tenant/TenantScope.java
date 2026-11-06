package br.com.ged.tenant;

import br.com.ged.config.AuthenticatedUser;
import br.com.ged.document.Document;
import br.com.ged.folder.Folder;
import br.com.ged.project.Project;
import br.com.ged.project.ProjectRepository;
import br.com.ged.review.Review;
import br.com.ged.user.Role;
import br.com.ged.user.User;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Isolacao entre escritorios. Todo acesso a dados sobe ate a pasta raiz, que referencia um
 * projeto, que pertence a um escritorio. O usuario so acessa dados do proprio escritorio;
 * SUPER_ADMIN acessa tudo.
 */
@Service
public class TenantScope {

    private final ProjectRepository projectRepository;

    public TenantScope(ProjectRepository projectRepository) {
        this.projectRepository = projectRepository;
    }

    public AuthenticatedUser currentPrincipal() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof AuthenticatedUser principal) {
            return principal;
        }
        return null;
    }

    /**
     * Escritorio do usuario atual. Vazio = sem restricao (SUPER_ADMIN ou chamadas sem
     * contexto autenticado, como testes unitarios e callbacks do editor).
     */
    public Optional<Long> currentOffice() {
        AuthenticatedUser principal = currentPrincipal();
        if (principal == null) {
            return Optional.empty();
        }
        return principal.officeScope();
    }

    public boolean isCurrentSuperAdmin() {
        AuthenticatedUser principal = currentPrincipal();
        return principal != null && principal.isSuperAdmin();
    }

    /**
     * Escritorio dono da pasta (via pasta raiz -> projeto). Vazio quando a raiz nao tem
     * projeto (dados sem escritorio: acessiveis apenas sem restricao, ex. SUPER_ADMIN).
     */
    public Optional<Long> officeOfFolder(Folder folder) {
        if (folder == null) {
            return Optional.empty();
        }
        Folder root = folder;
        while (root.getParent() != null) {
            root = root.getParent();
        }
        if (root.getProject() == null) {
            return Optional.empty();
        }
        return projectOffice(root.getProject().getId());
    }

    public Optional<Long> officeOfProjectId(Long projectId) {
        if (projectId == null) {
            return Optional.empty();
        }
        return projectOffice(projectId);
    }

    public Optional<Long> officeOfDocument(Document document) {
        return officeOfFolder(document.getFolder());
    }

    public Optional<Long> officeOfReview(Review review) {
        return officeOfDocument(review.getDocument());
    }

    public boolean canAccessOffice(Long resourceOffice) {
        Optional<Long> current = currentOffice();
        if (current.isEmpty()) {
            return true;
        }
        if (resourceOffice == null) {
            return false;
        }
        return current.get().equals(resourceOffice);
    }

    public boolean canAccessFolder(Folder folder) {
        return canAccessOffice(officeOfFolder(folder).orElse(null));
    }

    public boolean canAccessDocument(Document document) {
        return canAccessOffice(officeOfDocument(document).orElse(null));
    }

    public boolean canAccessReview(Review review) {
        return canAccessOffice(officeOfReview(review).orElse(null));
    }

    public boolean canAccessProject(Project project) {
        return canAccessOffice(project == null ? null : project.getOfficeId());
    }

    public boolean canAccessUser(User user) {
        if (user == null) {
            return false;
        }
        if (user.getRole() == Role.SUPER_ADMIN) {
            return isCurrentSuperAdmin();
        }
        return canAccessOffice(user.getOfficeId());
    }

    public void requireAccessToFolder(Folder folder) {
        if (!canAccessFolder(folder)) {
            throw new AccessDeniedException("Recurso nao pertence ao seu escritorio.");
        }
    }

    public void requireAccessToDocument(Document document) {
        if (!canAccessDocument(document)) {
            throw new AccessDeniedException("Recurso nao pertence ao seu escritorio.");
        }
    }

    public void requireAccessToReview(Review review) {
        if (!canAccessReview(review)) {
            throw new AccessDeniedException("Recurso nao pertence ao seu escritorio.");
        }
    }

    public void requireAccessToProject(Project project) {
        if (!canAccessProject(project)) {
            throw new AccessDeniedException("Recurso nao pertence ao seu escritorio.");
        }
    }

    public void requireAccessToUser(User user) {
        if (!canAccessUser(user)) {
            throw new AccessDeniedException("Recurso nao pertence ao seu escritorio.");
        }
    }

    /**
     * Compara o escritorio de um token (editor) com o escritorio dono do recurso.
     */
    public void requireOfficeMatch(Long tokenOfficeId, Long resourceOfficeId) {
        if (tokenOfficeId == null || resourceOfficeId == null || !tokenOfficeId.equals(resourceOfficeId)) {
            throw new AccessDeniedException("Token nao pertence ao escritorio do recurso.");
        }
    }

    private Optional<Long> projectOffice(Long projectId) {
        return projectRepository.findById(projectId).map(Project::getOfficeId);
    }
}
