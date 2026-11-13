USE k_fincheck;

ALTER TABLE policy_document
    ADD COLUMN publisher VARCHAR(100) NULL AFTER collected_at,
    ADD COLUMN license_type VARCHAR(30) NULL AFTER publisher;
