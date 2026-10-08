package br.com.ged.review;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ReviewCommentRepository extends JpaRepository<ReviewComment, Long> {

    List<ReviewComment> findByReviewOrderByCreatedAtAsc(Review review);

    long countByReview(Review review);
}
