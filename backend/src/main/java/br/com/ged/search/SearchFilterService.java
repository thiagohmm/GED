package br.com.ged.search;

import br.com.ged.document.Document;
import br.com.ged.document.DocumentRepository;
import br.com.ged.exception.BusinessException;
import br.com.ged.folder.Folder;
import br.com.ged.review.ReviewRepository;
import br.com.ged.tenant.TenantScope;
import br.com.ged.user.Role;
import br.com.ged.user.User;
import br.com.ged.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class SearchFilterService {

    private final DocumentRepository documentRepository;
    private final ReviewRepository reviewRepository;
    private final UserRepository userRepository;
    private final TenantScope tenantScope;

    @Transactional(readOnly = true)
    public List<SearchResultItem> filter(List<SearchResultItem> results, LocalDate createdFrom, LocalDate createdTo,
                                         Long attorneyId, Long reviewerId, String tag) {
        if (createdFrom != null && createdTo != null && createdFrom.isAfter(createdTo)) {
            throw new BusinessException("A data inicial deve ser anterior ou igual a data final.");
        }
        if (createdFrom == null && createdTo == null && attorneyId == null && reviewerId == null
                && (tag == null || tag.isBlank())) {
            return applyOfficeScope(results);
        }
        Set<Long> ids = results.stream().map(SearchResultItem::documentId).toList().stream().collect(java.util.stream.Collectors.toSet());
        Map<Long, Document> documents = new HashMap<>();
        documentRepository.findAllById(ids).forEach(document -> documents.put(document.getId(), document));
        Set<Long> reviewerDocuments = null;
        if (reviewerId != null) {
            User reviewer = userRepository.findById(reviewerId)
                    .filter(user -> user.getRole() == Role.REVISOR && user.isEnabled())
                    .orElseThrow(() -> new BusinessException("Selecione um revisor ativo."));
            if (!tenantScope.canAccessUser(reviewer)) {
                throw new AccessDeniedException("Selecione um revisor do seu escritorio.");
            }
            reviewerDocuments = new HashSet<>(reviewRepository.findDistinctDocumentIdsForParticipant(reviewer.getUsername()));
        }
        Long validatedAttorneyId = null;
        if (attorneyId != null) {
            User attorney = userRepository.findById(attorneyId)
                    .filter(user -> user.getRole() == Role.ADVOGADO && user.isEnabled())
                    .orElseThrow(() -> new BusinessException("Selecione um advogado ativo."));
            if (!tenantScope.canAccessUser(attorney)) {
                throw new AccessDeniedException("Selecione um advogado do seu escritorio.");
            }
            validatedAttorneyId = attorney.getId();
        }
        LocalDate endInclusive = createdTo;
        Set<Long> finalReviewerDocuments = reviewerDocuments;
        Long finalAttorneyId = validatedAttorneyId;
        String normalizedTag = tag == null ? null : tag.strip().toLowerCase();
        return results.stream().filter(result -> {
            Document document = documents.get(result.documentId());
            if (document == null) return false;
            if (!tenantScope.canAccessDocument(document)) return false;
            if (createdFrom != null && document.getCreatedAt().atZone(ZoneId.systemDefault()).toLocalDate().isBefore(createdFrom)) return false;
            if (endInclusive != null && document.getCreatedAt().atZone(ZoneId.systemDefault()).toLocalDate().isAfter(endInclusive)) return false;
            if (finalReviewerDocuments != null && !finalReviewerDocuments.contains(document.getId())) return false;
            if (finalAttorneyId != null && !finalAttorneyId.equals(assignedAttorneyId(document.getFolder()))) return false;
            if (normalizedTag != null && !normalizedTag.isBlank()
                    && document.getTags().stream().noneMatch(value -> value.toLowerCase().equals(normalizedTag))) return false;
            return true;
        }).toList();
    }

    /**
     * Remove da busca qualquer documento fora do escritorio do usuario.
     */
    private List<SearchResultItem> applyOfficeScope(List<SearchResultItem> results) {
        if (tenantScope.currentOffice().isEmpty()) {
            return results;
        }
        Set<Long> ids = results.stream().map(SearchResultItem::documentId).toList().stream().collect(java.util.stream.Collectors.toSet());
        Map<Long, Document> documents = new HashMap<>();
        documentRepository.findAllById(ids).forEach(document -> documents.put(document.getId(), document));
        return results.stream()
                .filter(result -> {
                    Document document = documents.get(result.documentId());
                    return document != null && tenantScope.canAccessDocument(document);
                })
                .toList();
    }

    private Long assignedAttorneyId(Folder folder) {
        Folder root = folder;
        while (root.getParent() != null) root = root.getParent();
        return root.getAssignedAttorney() == null ? null : root.getAssignedAttorney().getId();
    }
}
