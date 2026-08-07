package com.ats;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class AtsApplication {

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(AtsApplication.class);
        // One image, many roles: APP_ROLE picks which Kafka listeners are active
        // (each worker is gated by @ConditionalOnProperty on app.role) and only
        // the api role runs the HTTP server.
        String role = System.getenv().getOrDefault("APP_ROLE", "api");
        if (!"api".equals(role)) {
            app.setWebApplicationType(WebApplicationType.NONE);
        }
        app.run(args);
    }
}
