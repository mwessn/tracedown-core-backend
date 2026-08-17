CREATE TABLE ca_root (
    id                      SMALLSERIAL     PRIMARY KEY,
    certificate_pem         TEXT            NOT NULL,
    private_key_encrypted   TEXT            NOT NULL,
    private_key_iv          VARCHAR(64)     NOT NULL,
    expires_at              TIMESTAMP(0)    NOT NULL,
    created_at              TIMESTAMP(0)    NOT NULL DEFAULT now(),
    rotated_at              TIMESTAMP(0)
);
