package br.com.ged.search;

import br.com.ged.document.Document;
import br.com.ged.document.DocumentRepository;
import br.com.ged.exception.BusinessException;
import br.com.ged.folder.Folder;
import br.com.ged.review.ReviewRepository;
import br.com.ged.user.Role;
import br.com.ged.user.User;
import br.com.ged.user.UserRepository;
import lombok.RequiredArgsConstructor;
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

    @Transactional(readOnly = true)
    public List<SearchResultItem> filter(List<SearchResultItem> results, LocalDate createdFrom, LocalDate createdTo,
                                         Long attorneyId, Long reviewerId, String tag) {
        if (createdFrom != null && createdTo != null && createdFrom.isAfter(createdTo)) {
            throw new BusinessException("A data inicial deve ser anterior ou igual a data final.");
        }
        if (createdFrom == null && createdTo == null && attorneyId == null && reviewerId == null
                && (tag == null || tag.isBlank())) {
            return results;
        }
        Set<Long> ids = results.stream().map(SearchResultItem::documentId)
                .collect(java.util.stream.Collectors.toSet());
        Map<Long, Document> documents = new HashMap<>();
        documentRepository.findAllById(ids).forEach(document -> documents.put(document.getId(), document));
        Set<Long> reviewerDocuments = null;
        if (reviewerId != null) {
            User reviewer = userRepository.findById(reviewerId)
                    .filter(user -> user.getRole() == Role.REVISOR && user.isEnabled())
                    .orElseThrow(() -> new BusinessException("Selecione um revisor ativo."));
            reviewerDocuments = new HashSet<>(reviewRepository.findDistinctDocumentIdsForParticipant(reviewer.getUsername()));
        }
        LocalDate endInclusive = createdTo;
        Set<Long> finalReviewerDocuments = reviewerDocuments;
        String normalizedTag = tag == null ? null : tag.strip().toLowerCase();
        return results.stream().filter(result -> {
            Document document = documents.get(result.documentId());
            if (document == null) return false;
            if (createdFrom != null && document.getCreatedAt().atZone(ZoneId.systemDefault()).toLocalDate().isBefore(createdFrom)) return false;
            if (endInclusive != null && document.getCreatedAt().atZone(ZoneId.systemDefault()).toLocalDate().isAfter(endInclusive)) return false;
            if (attorneyId != null && !attorneyId.equals(assignedAttorneyId(document.getFolder()))) return false;
            if (finalReviewerDocuments != null && !finalReviewerDocuments.contains(document.getId())) return false;
            if (normalizedTag != null && !normalizedTag.isBlank()
                    && document.getTags().stream().noneMatch(value -> value.toLowerCase().equals(normalizedTag))) return false;
            return true;
        }).toList();
    }

    private Long assignedAttorneyId(Folder folder) {
        Folder root = folder;
        while (root.getParent() != null) root = root.getParent();
        return root.getAssignedAttorney() == null ? null : root.getAssignedAttorney().getId();
    }
}
