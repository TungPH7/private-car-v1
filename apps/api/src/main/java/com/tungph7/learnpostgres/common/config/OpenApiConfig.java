package com.tungph7.learnpostgres.common.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityScheme;

@Configuration
public class OpenApiConfig {

  @Bean
  public OpenAPI learnPostgresApi(
      @Value("${spring.application.name}") String appName,
      @Value("${info.app.version:0.0.1-SNAPSHOT}") String version) {
    return new OpenAPI()
        .info(
            new Info()
                .title("Learn PostgreSQL API")
                .description("Enterprise-grade Spring Boot + PostgreSQL learning project")
                .version(version)
                .contact(new Contact().name("TungPH").email("noreply@example.com"))
                .license(new License().name("MIT").url("https://opensource.org/license/mit")))
        // No servers: springdoc gives the origin of each request, so "Try it out" of Swagger UI
        // calls the API it was read from, on this machine, on the port of a lane, or on the host
        // of an environment behind Caddy (server.forward-headers-strategy: native).
        .components(
            new Components()
                .addSecuritySchemes(
                    "bearerAuth",
                    new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")
                        .description("JWT Bearer token (folder 10 sẽ enable thật)")));
    // Chưa addSecurityItem global — folder 10 sẽ làm khi thực sự bảo vệ endpoint.
  }
}
