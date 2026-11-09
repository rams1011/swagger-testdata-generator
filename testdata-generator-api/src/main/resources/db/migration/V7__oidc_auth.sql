-- OpenID Connect sign-in (Google and other issuers). Keep db/migration_mysql/V7 in step.

ALTER TABLE td_account ADD COLUMN oidc_issuer VARCHAR(255);
ALTER TABLE td_account ADD COLUMN oidc_subject VARCHAR(255);

CREATE UNIQUE INDEX ux_td_account_oidc ON td_account (oidc_issuer, oidc_subject);

CREATE TABLE td_oidc_state (
    state          VARCHAR(64)   NOT NULL,
    code_verifier  VARCHAR(128)  NOT NULL,
    nonce          VARCHAR(64)   NOT NULL,
    intent         VARCHAR(16)   NOT NULL,
    return_to      VARCHAR(500),
    expires_at     TIMESTAMP     NOT NULL,
    CONSTRAINT pk_td_oidc_state PRIMARY KEY (state)
);

CREATE TABLE td_oidc_exchange (
    exchange_id          VARCHAR(36)   NOT NULL,
    intent               VARCHAR(16)   NOT NULL,
    email                VARCHAR(320)  NOT NULL,
    display_name         VARCHAR(255)  NOT NULL,
    oidc_issuer          VARCHAR(255)  NOT NULL,
    oidc_subject         VARCHAR(255)  NOT NULL,
    needs_registration   BOOLEAN       NOT NULL,
    expires_at           TIMESTAMP     NOT NULL,
    CONSTRAINT pk_td_oidc_exchange PRIMARY KEY (exchange_id)
);
