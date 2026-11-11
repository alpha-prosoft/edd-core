CREATE TABLE outbox (
    service_name    VARCHAR(255) NOT NULL,
    request_id      UUID         NOT NULL,
    breadcrumbs     VARCHAR(255) NOT NULL,
    interaction_id  UUID,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    attempts        INT          NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_error      TEXT,
    failed_at       TIMESTAMPTZ,
    PRIMARY KEY (service_name, request_id, breadcrumbs)
);

CREATE INDEX outbox_due
    ON outbox (service_name, next_attempt_at)
    WHERE failed_at IS NULL;
