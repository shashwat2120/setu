package com.setu.shared;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Boot 4's split autoconfigure modules mean the webmvc starter alone doesn't
 * register a Jackson ObjectMapper bean (only jackson-databind is on the
 * classpath, not whichever autoconfigure module wires it up) — needed by
 * OutboxEventWriter/AuditLogWriter (governance) for JSONB payload
 * serialization. Defined explicitly rather than chasing the right
 * autoconfigure artifact name for this Boot version.
 */
@Configuration
public class JacksonConfig {

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper().findAndRegisterModules();
    }
}
