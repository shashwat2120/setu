package com.setu.shared.web;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Day 15 §9: the one piece of OpenAPI configuration this codebase adds by
 * hand — everything else (paths, request/response schemas, validation
 * constraints) springdoc derives automatically from the 39 existing
 * {@code @RestController} classes' own DTOs and {@code @RequestMapping}
 * annotations, so there is no per-endpoint documentation to keep in sync
 * or drift from the real implementation. Declares the one security
 * scheme every protected endpoint actually uses (a bearer JWT — see
 * {@code identity.infrastructure.security.SecurityConfig}) so
 * "Authorize" in the Swagger UI genuinely works against a real backend,
 * and applies it globally since {@code SecurityConfig}'s own default is
 * "authenticated unless explicitly public" — the same default this
 * spec now documents rather than contradicts.
 */
@Configuration
public class OpenApiConfig {

    private static final String BEARER_SCHEME = "bearerAuth";

    @Bean
    public OpenAPI setuOpenApi() {
        return new OpenAPI()
            .info(new Info()
                .title("Setu API")
                .description("Multi-tenant college management platform — academic, finance, career, "
                    + "student-success, and AI-explanation modules. Every endpoint requires a bearer "
                    + "JWT unless explicitly noted as public (auth, health).")
                .version("sprint-5"))
            .components(new Components()
                .addSecuritySchemes(BEARER_SCHEME, new SecurityScheme()
                    .type(SecurityScheme.Type.HTTP)
                    .scheme("bearer")
                    .bearerFormat("JWT")))
            .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME));
    }
}
