package com.finflow.transaction_service.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI finFlowOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("FinFlow Transaction Service API")
                        .version("v1.0.0")
                        .description("""
                                REST API for account management and financial transfers.

                                The transfer endpoint supports idempotency keys to prevent
                                duplicate financial operations during request retries.
                                """)
                        .contact(new Contact()
                                .name("FinFlow Engineering")));
    }
}