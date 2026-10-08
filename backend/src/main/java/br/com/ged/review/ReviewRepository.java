package br.com.ged.review;

import br.com.ged.document.Document;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ReviewRepository extends JpaRepository<Review, Long> {

    Optional<Review> findTopByDocumentOrderByRoundDesc(Document document);

    List<Review> findByVerdictOrderByCreatedAtDesc(ReviewVerdict verdict);

    List<Review> findTop10ByVerdictInAndDecidedAtIsNotNullOrderByDecidedAtDesc(List<ReviewVerdict> verdicts);

    List<Review> findByDocumentAndVerdict(Document document, ReviewVerdict verdict);

    List<Review> findByDocumentOrderByRoundAsc(Document document);

    @Query("select distinct r.document.id from Review r where r.createdBy = :username or r.decidedBy = :username")
    List<Long> findDistinctDocumentIdsForParticipant(@Param("username") String username);
}
