-- 기존 회원에게 동의를 소급하지 않습니다. 신규 회원은 계정 생성과 함께 동의한 판본과 UTC 시각을 기록합니다.
ALTER TABLE users
    ADD COLUMN accepted_terms_version VARCHAR(32),
    ADD COLUMN terms_accepted_at TIMESTAMP(6),
    ADD CONSTRAINT ck_users_terms_consent CHECK (
        (accepted_terms_version IS NULL AND terms_accepted_at IS NULL)
        OR (accepted_terms_version IS NOT NULL AND btrim(accepted_terms_version) <> '' AND terms_accepted_at IS NOT NULL)
    );
