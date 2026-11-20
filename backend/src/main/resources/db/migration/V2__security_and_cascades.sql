-- V2__security_and_cascades.sql
-- Endurecimento de seguranca multi-tenant, invalidacao de JWT pos-troca de senha
-- e integridade referencial em cascata para exclusao segura de documentos.

ALTER TABLE users ADD COLUMN token_version INTEGER NOT NULL DEFAULT 0;

ALTER TABLE users ADD CONSTRAINT chk_users_office_by_role
    CHECK ((role = 'SUPER_ADMIN' AND office_id IS NULL) OR (role <> 'SUPER_ADMIN' AND office_id IS NOT NULL));

CREATE UNIQUE INDEX uk_users_username_lower ON users (LOWER(username));
CREATE INDEX idx_users_office ON users (office_id);

ALTER TABLE document_versions DROP CONSTRAINT fk_document_versions_document;
ALTER TABLE document_versions ADD CONSTRAINT fk_document_versions_document
    FOREIGN KEY (document_id) REFERENCES documents (id) ON DELETE CASCADE;

ALTER TABLE document_status_changes DROP CONSTRAINT fk_document_status_changes_document;
ALTER TABLE document_status_changes ADD CONSTRAINT fk_document_status_changes_document
    FOREIGN KEY (document_id) REFERENCES documents (id) ON DELETE CASCADE;

ALTER TABLE document_tags DROP CONSTRAINT fk_document_tags_document;
ALTER TABLE document_tags ADD CONSTRAINT fk_document_tags_document
    FOREIGN KEY (document_id) REFERENCES documents (id) ON DELETE CASCADE;

ALTER TABLE reviews DROP CONSTRAINT fk_reviews_document;
ALTER TABLE reviews ADD CONSTRAINT fk_reviews_document
    FOREIGN KEY (document_id) REFERENCES documents (id) ON DELETE CASCADE;

ALTER TABLE review_comments DROP CONSTRAINT fk_review_comments_review;
ALTER TABLE review_comments ADD CONSTRAINT fk_review_comments_review
    FOREIGN KEY (review_id) REFERENCES reviews (id) ON DELETE CASCADE;

CREATE INDEX idx_review_comments_review ON review_comments (review_id);
