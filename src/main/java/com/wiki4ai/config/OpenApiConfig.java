package com.wiki4ai.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI/Swagger configuration for the Wiki4AI API documentation.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI customOpenAPI() {
        final String securitySchemeName = "bearerAuth";

        return new OpenAPI()
                .info(new Info()
                        .title("Wiki4AI API")
                        .version("1.0.0")
                        .description("Wiki system for AI agents and developers. " +
                                "Allows creating projects/topics, storing markdown documents, " +
                                "linking them together and visualizing connections via web UI.")
                        .contact(new Contact()
                                .name("Wiki4AI Team")
                                .email("contact@wiki4ai.com"))
                        .license(new License()
                                .name("MIT License")
                                .url("https://opensource.org/licenses/MIT")))
                ;
    }
}
