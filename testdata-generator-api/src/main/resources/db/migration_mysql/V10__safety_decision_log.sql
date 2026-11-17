-- Production safety review (PR 6): one row per request the safety check stopped (Enforce) or would have stopped
-- (Shadow). Kept 90 days. Keep db/migration/V10 in step.
CREATE TABLE td_safety_decision (
    decision_id     VARCHAR(36)   NOT NULL,
    org_id          VARCHAR(36)   NOT NULL,
    occurred_at     DATETIME(3)   NOT NULL,
    environment_id  VARCHAR(36),
    case_id         VARCHAR(64),
    http_method     VARCHAR(16)   NOT NULL,
    path_template   VARCHAR(500),
    target_id       VARCHAR(36),
    target_label    VARCHAR(300)  NOT NULL,
    block_code      VARCHAR(40)   NOT NULL,
    shadow          BOOLEAN       NOT NULL,
    CONSTRAINT pk_td_safety_decision PRIMARY KEY (decision_id),
    CONSTRAINT fk_td_safety_decision_org FOREIGN KEY (org_id)
        REFERENCES td_organization (org_id) ON DELETE CASCADE
);

CREATE INDEX ix_td_safety_decision_org ON td_safety_decision (org_id, occurred_at);
