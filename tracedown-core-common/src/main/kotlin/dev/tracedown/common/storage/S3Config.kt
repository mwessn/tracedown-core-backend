package dev.tracedown.common.storage

/**
 * Configuration for connecting to an S3-compatible object store.
 *
 * Works with any S3-compatible service: Cloudflare R2, MinIO, Backblaze B2,
 * DigitalOcean Spaces, etc.
 */
data class S3Config(
    /** Endpoint URL, e.g. ``https://<account>.r2.cloudflarestorage.com`` */
    val endpoint: String,
    /** Access key ID. */
    val accessKey: String,
    /** Secret access key. */
    val secretKey: String,
)
