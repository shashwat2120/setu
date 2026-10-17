package com.setu.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What an entry point's start-up defaults can and cannot do. Every service's {@code main} hands Spring Boot a map of defaults (port, schema, the
 * service's name ...) with {@link SpringApplication#setDefaultProperties}. Those have the LOWEST precedence of any property source, below the
 * packaged {@code application.yml}, so a default for a key the yml also sets is silently ignored: the yml wins. This pins down the rule the
 * entry points follow, so it is not rediscovered the hard way: a service that needs a different value for a key the yml defines must set the
 * placeholder the yml reads ({@code SERVER_PORT}, {@code SERVER_MAX_THREADS}), not the key itself.
 */
class EntryPointDefaultsPrecedenceTests {

    @Configuration
    static class Empty {
    }

    private String resolve(Map<String, Object> defaults, String key) {
        SpringApplication application = new SpringApplication(Empty.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setDefaultProperties(defaults);
        try (ConfigurableApplicationContext context = application.run("--spring.main.banner-mode=off")) {
            return context.getEnvironment().getProperty(key);
        }
    }

    @Test
    void aDefaultForAKeyTheYmlAlsoSetsLosesToTheYml() {
        assertThat(resolve(Map.of("server.port", "8088"), "server.port")).as("the yml's server.port wins over the entry point's default").isEqualTo("8080");
    }

    @Test
    void aDefaultForThePlaceholderTheYmlReadsDoesTakeEffect() {
        assertThat(resolve(Map.of("SERVER_PORT", "8088"), "server.port")).isEqualTo("8088");
        assertThat(resolve(Map.of("SERVER_MAX_THREADS", "200"), "server.tomcat.threads.max")).isEqualTo("200");
    }

    @Test
    void aDefaultForAKeyNoYmlSetsIsSimplyUsed() {
        assertThat(resolve(Map.of("setu.service", "ai"), "setu.service")).isEqualTo("ai");
    }
}
