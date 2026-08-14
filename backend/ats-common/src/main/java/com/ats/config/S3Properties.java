package com.ats.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * endpoint: where backend containers reach MinIO (http://minio:9000 in compose).
 * publicEndpoint: what presigned URLs are signed for — the address the
 * uploading client actually hits (http://localhost:9000 from the host).
 */
@ConfigurationProperties(prefix = "app.s3")
public record S3Properties(String endpoint, String publicEndpoint, String accessKey, String secretKey, String bucket) {
}
