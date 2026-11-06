package br.com.ged.review;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import br.com.ged.document.DocumentResponse;
import br.com.ged.config.AuthenticatedUser;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class ReviewController {

    private final ReviewService reviewService;

    @GetMapping("/api/reviews/queue")
    @PreAuthorize("hasAnyRole('REVISOR','ADMIN','SUPER_ADMIN')")
    public ReviewQueueResponse queue() {
        return reviewService.queue();
    }

    @GetMapping("/api/reviews/{id}")
    public ReviewResponse detail(@PathVariable Long id) {
        return reviewService.detail(id);
    }

    @PostMapping("/api/reviews/{id}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    public ReviewCommentResponse addComment(@PathVariable Long id, @Valid @RequestBody AddCommentRequest request,
                                            @AuthenticationPrincipal AuthenticatedUser actor) {
        return reviewService.addComment(id, request, actor.username());
    }

    @PostMapping("/api/reviews/{id}/decide")
    @PreAuthorize("hasAnyRole('REVISOR','ADMIN','SUPER_ADMIN')")
    public DocumentResponse decide(@PathVariable Long id,
                                   @Valid @RequestBody DecideReviewRequest request,
                                   @AuthenticationPrincipal AuthenticatedUser actor) {
        return reviewService.decide(id, request, actor.username());
    }

    @GetMapping("/api/documents/{documentId}/reviews")
    public List<DocumentReviewsItem> reviewsOfDocument(@PathVariable Long documentId) {
        return reviewService.reviewsOfDocument(documentId);
    }
}
