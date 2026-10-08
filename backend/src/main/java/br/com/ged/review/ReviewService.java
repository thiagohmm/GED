package br.com.ged.review;

import br.com.ged.document.Document;
import br.com.ged.document.DocumentResponse;
import br.com.ged.document.DocumentRepository;
import br.com.ged.document.DocumentStatus;
import br.com.ged.document.DocumentStatusHistoryService;
import br.com.ged.exception.ConflictException;
import br.com.ged.exception.NotFoundException;
import br.com.ged.folder.FolderService;
import br.com.ged.user.Role;
import br.com.ged.user.RoleChecker;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class ReviewService {

    private final ReviewRepository reviewRepository;
    private final ReviewCommentRepository commentRepository;
    private final DocumentRepository documentRepository;
    private final FolderService folderService;
    private final RoleChecker roleChecker;
    private final DocumentStatusHistoryService statusHistoryService;

    @Transactional
    public Review openReview(Document document, String openedBy) {
        int nextRound = reviewRepository.findTopByDocumentOrderByRoundDesc(document)
                .map(Review::getRound)
                .orElse(0) + 1;
        int fromVersion = reviewRepository.findTopByDocumentOrderByRoundDesc(document)
                .map(Review::getToVersion)
                .orElse(1);
        return reviewRepository.save(new Review(document, nextRound, fromVersion, document.getCurrentVersion(), openedBy));
    }

    @Transactional(readOnly = true)
    public ReviewQueueResponse queue() {
        List<Review> pending = reviewRepository.findByVerdictOrderByCreatedAtDesc(ReviewVerdict.PENDENTE);
        List<Review> recentlyDecided = reviewRepository.findTop10ByVerdictInAndDecidedAtIsNotNullOrderByDecidedAtDesc(
                List.of(ReviewVerdict.APROVADO, ReviewVerdict.CORRECOES_SOLICITADAS));
        return new ReviewQueueResponse(toItems(pending), toItems(recentlyDecided));
    }

    @Transactional(readOnly = true)
    public ReviewResponse detail(Long reviewId) {
        Review review = findById(reviewId);
        return ReviewResponse.from(review, commentsOf(review));
    }

    @Transactional
    public ReviewCommentResponse addComment(Long reviewId, AddCommentRequest request, String username) {
        Review review = findById(reviewId);
        requireOpen(review);
        ReviewComment comment = new ReviewComment(review, username, request.body().strip(),
                request.diffLineIndex(), cleanLineContent(request.diffLineContent()));
        return ReviewCommentResponse.from(commentRepository.save(comment));
    }

    @Transactional
    public DocumentResponse decide(Long reviewId, DecideReviewRequest request, String username) {
        roleChecker.requireAny(Set.of(Role.REVISOR, Role.ADMIN));
        Review review = findById(reviewId);
        if (review.getVerdict() != ReviewVerdict.PENDENTE) {
            throw new ConflictException("Esta revisao ja foi decidida.");
        }
        Document document = review.getDocument();
        if (document.getStatus() != DocumentStatus.EM_REVISAO) {
            throw new ConflictException("O documento desta revisao nao esta mais em revisao.");
        }
        DocumentStatus target = request.verdict() == ReviewVerdict.APROVADO
                ? DocumentStatus.PRONTO_PARA_PROTOCOLO
                : DocumentStatus.EDITANDO;
        String note = composeDecisionDescription(review, request, target);
        DocumentResponse response = statusHistoryService.changeStatus(document, target, note, username);
        review.setVerdict(request.verdict());
        review.setDecidedBy(username);
        review.setDecidedAt(Instant.now());
        review.setSummary(blankToNull(request.summary()));
        reviewRepository.save(review);
        return response;
    }

    @Transactional(readOnly = true)
    public List<DocumentReviewsItem> reviewsOfDocument(Long documentId) {
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new NotFoundException("Documento nao encontrado."));
        return reviewRepository.findByDocumentOrderByRoundAsc(document).stream()
                .map(review -> new DocumentReviewsItem(
                        review.getId(),
                        document.getStatus(),
                        review.getRound(),
                        review.getFromVersion(),
                        review.getToVersion(),
                        review.getVerdict(),
                        review.getCreatedBy(),
                        review.getCreatedAt(),
                        review.getDecidedBy(),
                        review.getDecidedAt(),
                        commentRepository.countByReview(review)))
                .toList();
    }

    private ReviewQueueItem toQueueItem(Review review) {
        return ReviewQueueItem.from(review, folderService.pathOf(review.getDocument().getFolder().getId()));
    }

    private List<ReviewQueueItem> toItems(List<Review> reviews) {
        return reviews.stream().map(this::toQueueItem).toList();
    }

    private void requireOpen(Review review) {
        if (review.getVerdict() != ReviewVerdict.PENDENTE) {
            throw new ConflictException("Esta revisao ja foi decidida e nao aceita novos comentarios.");
        }
    }

    private Review findById(Long reviewId) {
        return reviewRepository.findById(reviewId)
                .orElseThrow(() -> new NotFoundException("Revisao nao encontrada."));
    }

    private List<ReviewCommentResponse> commentsOf(Review review) {
        return commentRepository.findByReviewOrderByCreatedAtAsc(review).stream()
                .map(ReviewCommentResponse::from)
                .toList();
    }

    private String composeDecisionDescription(Review review, DecideReviewRequest request, DocumentStatus target) {
        StringBuilder description = new StringBuilder("Status: %s -> %s - Revisao #%d: %s".formatted(
                DocumentStatus.EM_REVISAO, target, review.getRound(), request.verdict()));
        if (request.summary() != null && !request.summary().isBlank()) {
            description.append(" - ").append(request.summary().strip());
        }
        return description.toString();
    }

    private String cleanLineContent(String diffLineContent) {
        return diffLineContent == null || diffLineContent.isBlank() ? null : diffLineContent.strip();
    }

    private String blankToNull(String summary) {
        return summary == null || summary.isBlank() ? null : summary.strip();
    }
}
