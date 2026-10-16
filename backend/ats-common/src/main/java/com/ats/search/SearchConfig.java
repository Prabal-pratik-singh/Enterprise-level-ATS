package com.ats.search;

import java.net.http.HttpClient;
import java.time.Duration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(SearchProperties.class)
public class SearchConfig {

    /** One shared client — services without app.search.base-url get none. */
    @Bean
    @ConditionalOnProperty(prefix = "app.search", name = "base-url")
    public OpenSearchClient openSearchClient(SearchProperties props, ObjectMapper mapper) {
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(props.connectTimeoutSeconds()))
                .build();
        return new OpenSearchClient(http, mapper, props);
    }
}
