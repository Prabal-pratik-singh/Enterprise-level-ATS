package com.ats.config;

import java.net.URI;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@Configuration
@EnableConfigurationProperties(S3Properties.class)
public class S3Config {

    /**
     * AWS SDK v2 client pointed at MinIO. The cloud swap to real S3 is an env
     * change (endpoint + credentials), not a code change. Path-style access is
     * required by MinIO; the region value is a placeholder MinIO ignores.
     */
    @Bean
    public S3Client s3Client(S3Properties props) {
        return S3Client.builder()
                .endpointOverride(URI.create(props.endpoint()))
                .region(Region.US_EAST_1)
                .credentialsProvider(credentials(props))
                .forcePathStyle(true)
                .build();
    }

    /**
     * Signs presigned URLs against the PUBLIC endpoint: the signature covers
     * the Host header, so it must match the address the uploader's HTTP client
     * uses (localhost:9000 from the host machine), not the in-network alias.
     */
    @Bean
    public S3Presigner s3Presigner(S3Properties props) {
        return S3Presigner.builder()
                .endpointOverride(URI.create(props.publicEndpoint()))
                .region(Region.US_EAST_1)
                .credentialsProvider(credentials(props))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();
    }

    private static AwsCredentialsProvider credentials(S3Properties props) {
        return StaticCredentialsProvider.create(
                AwsBasicCredentials.create(props.accessKey(), props.secretKey()));
    }
}
