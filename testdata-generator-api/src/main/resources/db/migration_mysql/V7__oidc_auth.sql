-- Same as db/migration/V7__oidc_auth.sql (MySQL types).

ALTER TABLE td_account
    ADD COLUMN oidc_issuer  VARCHAR(255) NULL,
    ADD COLUMN oidc_subject VARCHAR(255) NULL;

CREATE UNIQUE INDEX ux_td_account_oidc ON td_account (oidc_issuer, oidc_subject);

CREATE TABLE td_oidc_state (
    state          VARCHAR(64)   NOT NULL,
    code_verifier  VARCHAR(128)  NOT NULL,
    nonce          VARCHAR(64)   NOT NULL,
    intent         VARCHAR(16)   NOT NULL,
    return_to      VARCHAR(500)  NULL,
    expires_at     DATETIME(3)   NOT NULL,
    CONSTRAINT pk_td_oidc_state PRIMARY KEY (state)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE td_oidc_exchange (
    exchange_id          VARCHAR(36)   NOT NULL,
    intent               VARCHAR(16)   NOT NULL,
    email                VARCHAR(320)  NOT NULL,
    display_name         VARCHAR(255)  NOT NULL,
    oidc_issuer          VARCHAR(255)  NOT NULL,
    oidc_subject         VARCHAR(255)  NOT NULL,
    needs_registration   BOOLEAN       NOT NULL,
    expires_at           DATETIME(3)   NOT NULL,
    CONSTRAINT pk_td_oidc_exchange PRIMARY KEY (exchange_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
