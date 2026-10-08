package br.com.ged.folder;

import br.com.ged.document.Document;
import br.com.ged.document.DocumentRepository;
import br.com.ged.document.DocumentVersion;
import br.com.ged.exception.BusinessException;
import br.com.ged.exception.ConflictException;
import br.com.ged.exception.NotFoundException;
import br.com.ged.project.Project;
import br.com.ged.project.ProjectRepository;
import br.com.ged.search.SearchGateway;
import br.com.ged.user.Role;
import br.com.ged.user.RoleChecker;
import br.com.ged.user.User;
import br.com.ged.user.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class FolderService {

    private final FolderRepository folderRepository;
    private final DocumentRepository documentRepository;
    private final ProjectRepository projectRepository;
    private final UserRepository userRepository;
    private final RoleChecker roleChecker;
    private final SearchGateway searchGateway;

    public FolderService(FolderRepository folderRepository, DocumentRepository documentRepository,
                         ProjectRepository projectRepository, UserRepository userRepository,
                         RoleChecker roleChecker, SearchGateway searchGateway) {
        this.folderRepository = folderRepository;
        this.documentRepository = documentRepository;
        this.projectRepository = projectRepository;
        this.userRepository = userRepository;
        this.roleChecker = roleChecker;
        this.searchGateway = searchGateway;
    }

    @Transactional
    public FolderNode create(CreateFolderRequest request, String username) {
        Folder parent = resolveParent(request.parentId());
        String name = normalizeName(request.name());
        rejectDuplicateName(name, parent);
        Folder folder = new Folder(name, parent, username);
        if (parent == null) {
            roleChecker.requireAny(java.util.Set.of(Role.ADMIN, Role.PROTOCOLADOR));
            if (request.projectId() == null || request.assignedAttorneyId() == null) {
                throw new BusinessException("Informe o projeto e o advogado responsavel pela pasta raiz.");
            }
            Project project = projectRepository.findById(request.projectId())
                    .orElseThrow(() -> new NotFoundException("Projeto nao encontrado."));
            User attorney = userRepository.findById(request.assignedAttorneyId())
                    .orElseThrow(() -> new NotFoundException("Advogado nao encontrado."));
            if (attorney.getRole() != Role.ADVOGADO || !attorney.isEnabled()) {
                throw new BusinessException("Selecione um advogado ativo.");
            }
            folder.setProject(project);
            folder.setAssignedAttorney(attorney);
        }
        Folder saved = folderRepository.save(folder);
        return toNode(saved);
    }

    @Transactional
    public FolderNode rename(Long id, RenameFolderRequest request) {
        Folder folder = findById(id);
        String name = normalizeName(request.name());
        rejectDuplicateName(name, folder.getParent(), folder.getId());
        folder.setName(name);
        reindexSubtree(folder);
        return toNode(folder);
    }

    @Transactional
    public FolderNode moveFolder(Long id, Long targetFolderId) {
        Folder folder = findById(id);
        Folder target = findById(targetFolderId);
        if (folder.getId().equals(target.getId())) {
            throw new BusinessException("Nao e possivel mover a pasta para dentro de si mesma.");
        }
        for (Folder step = target; step != null; step = step.getParent()) {
            if (folder.getId().equals(step.getId())) {
                throw new BusinessException("Nao e possivel mover a pasta para dentro de uma subpasta dela mesma.");
            }
        }
        folderRepository.findByParentAndNameIgnoreCase(target, folder.getName())
                .ifPresent(conflicting -> {
                    throw new ConflictException("Ja existe uma pasta com este nome na pasta destino.");
                });
        folder.setParent(target);
        reindexSubtree(folder);
        return toNode(folder);
    }

    private void reindexSubtree(Folder root) {
        List<Long> folderIds = new ArrayList<>();
        collectSubtreeIds(root, folderIds);
        for (Long folderId : folderIds) {
            String folderPath = pathOf(folderId);
            for (Document document : documentRepository.findByFolderIdSortedByName(folderId)) {
                for (DocumentVersion version : document.getVersions()) {
                    searchGateway.indexVersion(version, folderPath);
                }
            }
        }
    }

    private void collectSubtreeIds(Folder root, List<Long> out) {
        out.add(root.getId());
        for (Folder child : folderRepository.findByParentId(root.getId())) {
            collectSubtreeIds(child, out);
        }
    }

    @Transactional
    public FolderNode assignRoot(Long id, AssignRootFolderRequest request) {
        roleChecker.requireAny(java.util.Set.of(Role.ADMIN, Role.PROTOCOLADOR));
        Folder folder = findById(id);
        if (folder.getParent() != null) {
            throw new BusinessException("Projeto e advogado sao definidos na pasta raiz.");
        }
        Project project = projectRepository.findById(request.projectId())
                .orElseThrow(() -> new NotFoundException("Projeto nao encontrado."));
        User attorney = userRepository.findById(request.assignedAttorneyId())
                .orElseThrow(() -> new NotFoundException("Advogado nao encontrado."));
        if (attorney.getRole() != Role.ADVOGADO || !attorney.isEnabled()) {
            throw new BusinessException("Selecione um advogado ativo.");
        }
        folder.setProject(project);
        folder.setAssignedAttorney(attorney);
        return toNode(folder);
    }

    @Transactional
    public void delete(Long id) {
        Folder folder = findById(id);
        if (folderRepository.existsByParentId(id)) {
            throw new ConflictException("A pasta possui subpastas. Exclua as subpastas primeiro.");
        }
        if (documentRepository.existsByFolderId(id)) {
            throw new ConflictException("A pasta possui documentos. Exclua os documentos primeiro.");
        }
        folderRepository.delete(folder);
    }

    @Transactional(readOnly = true)
    public List<FolderNode> tree() {
        Map<Long, FolderNode> nodes = new HashMap<>();
        List<Folder> folders = folderRepository.findAll().stream()
                .sorted(Comparator.comparing(Folder::getName, String.CASE_INSENSITIVE_ORDER))
                .toList();
        folders.forEach(folder -> nodes.put(folder.getId(), toNode(folder)));
        folders.forEach(folder -> linkToParent(nodes, folder));
        return nodes.values().stream()
                .filter(node -> node.parentId() == null)
                .toList();
    }

    @Transactional(readOnly = true)
    public Folder findById(Long id) {
        return folderRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Pasta nao encontrada."));
    }

    @Transactional(readOnly = true)
    public String pathOf(Long folderId) {
        LinkedList<String> names = new LinkedList<>();
        for (Folder step = findById(folderId); step != null; step = step.getParent()) {
            names.addFirst(step.getName());
        }
        return String.join(" / ", names);
    }

    private Folder resolveParent(Long parentId) {
        return parentId == null ? null : findById(parentId);
    }

    private void rejectDuplicateName(String name, Folder parent, Long ignoredFolderId) {
        conflictingFolder(name, parent)
                .filter(conflicting -> !conflicting.getId().equals(ignoredFolderId))
                .ifPresent(conflicting -> {
                    throw new ConflictException("Ja existe uma pasta com este nome neste nivel.");
                });
    }

    private void rejectDuplicateName(String name, Folder parent) {
        rejectDuplicateName(name, parent, null);
    }

    private Optional<Folder> conflictingFolder(String name, Folder parent) {
        return parent == null
                ? folderRepository.findByParentIsNullAndNameIgnoreCase(name)
                : folderRepository.findByParentAndNameIgnoreCase(parent, name);
    }

    private String normalizeName(String name) {
        String trimmed = name.trim();
        if (trimmed.isEmpty()) {
            throw new ConflictException("O nome da pasta e obrigatorio.");
        }
        return trimmed;
    }

    private Long parentIdOf(Folder folder) {
        return folder.getParent() == null ? null : folder.getParent().getId();
    }

    private FolderNode toNode(Folder folder) {
        Folder root = folder;
        while (root.getParent() != null) {
            root = root.getParent();
        }
        Project project = root.getProject();
        User attorney = root.getAssignedAttorney();
        return FolderNode.leaf(folder.getId(), folder.getName(), parentIdOf(folder),
                project == null ? null : project.getId(), project == null ? null : project.getName(),
                attorney == null ? null : attorney.getId(), attorney == null ? null : attorney.getUsername());
    }

    private void linkToParent(Map<Long, FolderNode> nodes, Folder folder) {
        if (folder.getParent() != null) {
            nodes.get(folder.getParent().getId()).children().add(nodes.get(folder.getId()));
        }
    }
}
