package br.com.ged.review;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

@Entity
@Table(name = "review_comments")
@Getter
@Setter
@NoArgsConstructor
public class ReviewComment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "review_id", nullable = false)
    private Review review;

    @Column(name = "author", length = 60, nullable = false)
    private String author;

    @Column(nullable = false, columnDefinition = "text")
    private String body;

    @Column(name = "diff_line_index")
    private Integer diffLineIndex;

    @Column(name = "diff_line_content", length = 500)
    private String diffLineContent;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public ReviewComment(Review review, String author, String body, Integer diffLineIndex, String diffLineContent) {
        this.review = review;
        this.author = author;
        this.body = body;
        this.diffLineIndex = diffLineIndex;
        this.diffLineContent = diffLineContent;
    }
}
