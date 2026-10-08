package br.com.ged.workflow;

import br.com.ged.document.Document;
import br.com.ged.document.DocumentRepository;
import br.com.ged.document.DocumentResponse;
import br.com.ged.document.DocumentStatus;
import br.com.ged.document.DocumentStatusHistoryService;
import br.com.ged.exception.BusinessException;
import br.com.ged.exception.NotFoundException;
import br.com.ged.review.Review;
import br.com.ged.review.ReviewService;
import br.com.ged.user.Role;
import br.com.ged.user.RoleChecker;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class WorkflowService {

    private static final String REVIEW_EXIT_MESSAGE =
            "A saida do status EM_REVISAO acontece apenas pela decisao da revisao.";

    private final DocumentRepository documentRepository;
    private final ReviewService reviewService;
    private final RoleChecker roleChecker;
    private final DocumentStatusHistoryService statusHistoryService;

    public List<DocumentStatus> allowedTargetsOf(DocumentStatus from) {
        return switch (from) {
            case EDITANDO -> List.of(DocumentStatus.EM_REVISAO);
            case EM_REVISAO -> List.of();
            case PRONTO_PARA_PROTOCOLO -> List.of(DocumentStatus.PROTOCOLADO);
            case PROTOCOLADO -> List.of(DocumentStatus.EDITANDO);
        };
    }

    public List<DocumentStatus> allowedTransitionsFor(DocumentStatus from) {
        return allowedTargetsOf(from).stream()
                .filter(target -> {
                    Set<Role> required = requiredRolesFor(from, target);
                    return required.isEmpty() || roleChecker.currentHasAny(required);
                })
                .toList();
    }

    public DocumentResponse withTransitionOptions(DocumentResponse response) {
        return response.withTransitions(allowedTransitionsFor(response.status()));
    }

    @Transactional
    public DocumentResponse requestStatusChange(Long documentId, DocumentStatus to, String note, String username) {
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new NotFoundException("Documento nao encontrado."));
        DocumentStatus from = document.getStatus();
        if (from == DocumentStatus.EM_REVISAO) {
            throw new BusinessException(REVIEW_EXIT_MESSAGE);
        }
        List<DocumentStatus> targets = allowedTargetsOf(from);
        if (!targets.contains(to)) {
            throw new BusinessException("Transicao de %s para %s nao e permitida.".formatted(from, to));
        }
        requireRoleFor(from, to);
        Review openedReview = to == DocumentStatus.EM_REVISAO
                ? reviewService.openReview(document, username)
                : null;
        String historyNote = note;
        if (openedReview != null) {
            String opened = "Revisao #" + openedReview.getRound() + " aberta";
            historyNote = note == null || note.isBlank() ? opened : opened + " — " + note.strip();
        }
        return statusHistoryService.changeStatus(document, to, historyNote, username);
    }

    private void requireRoleFor(DocumentStatus from, DocumentStatus to) {
        Set<Role> required = requiredRolesFor(from, to);
        if (required.isEmpty()) {
            return;
        }
        roleChecker.requireAny(required);
    }

    private Set<Role> requiredRolesFor(DocumentStatus from, DocumentStatus to) {
        return switch (from) {
            case PRONTO_PARA_PROTOCOLO -> Set.of(Role.PROTOCOLADOR, Role.ADMIN);
            case PROTOCOLADO -> Set.of(Role.REVISOR, Role.ADMIN);
            default -> Set.of();
        };
    }
}
