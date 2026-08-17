-- Per-organization data-encryption keys (DEKs) for envelope encryption of
-- secret variable values.
--
-- wrapped_dek holds the org's random AES-256 DEK, wrapped (AES-GCM) with the
-- platform key (PLATFORM_AES_KEY) acting as the key-encryption key. Secret
-- variable values are encrypted with the org DEK, so deleting this row
-- renders every secret ciphertext of the organization permanently
-- undecryptable (crypto-shredding) — which is exactly what the purge job
-- relies on when erasing an organization.
--
-- key_version tracks the KEK-wrap generation for future key rotation.
CREATE TABLE org_encryption_keys (
    org_id      UUID            PRIMARY KEY REFERENCES organizations(id) ON DELETE CASCADE,
    wrapped_dek TEXT            NOT NULL,
    key_version INT             NOT NULL DEFAULT 1,
    created_at  TIMESTAMP(0)    NOT NULL DEFAULT now()
);
