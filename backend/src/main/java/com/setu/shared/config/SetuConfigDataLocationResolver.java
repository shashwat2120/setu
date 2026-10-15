package com.setu.shared.config;

import org.springframework.boot.context.config.ConfigDataLocation;
import org.springframework.boot.context.config.ConfigDataLocationResolver;
import org.springframework.boot.context.config.ConfigDataLocationResolverContext;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;

import java.net.URI;
import java.util.List;

/**
 * Teaches {@code spring.config.import} the {@code setu-config:} location:
 * <pre>spring.config.import=optional:setu-config:http://localhost:8888</pre>
 * Which application and profiles to ask for, and the token to prove it, come from the properties the application already has at this point
 * (environment variables, command line, the earlier files): {@code setu.config.application} (default: {@code spring.application.name}
 * without a trailing {@code -service}), {@code spring.profiles.active}, and {@code setu.config.token} (environment variable
 * {@code SETU_CONFIG_TOKEN}). Registered through {@code META-INF/spring.factories}; inert unless an application imports such a location.
 */
public class SetuConfigDataLocationResolver implements ConfigDataLocationResolver<SetuConfigDataResource> {

    static final String PREFIX = "setu-config:";

    @Override
    public boolean isResolvable(ConfigDataLocationResolverContext context, ConfigDataLocation location) {
        return location.hasPrefix(PREFIX);
    }

    @Override
    public List<SetuConfigDataResource> resolve(ConfigDataLocationResolverContext context, ConfigDataLocation location) {
        URI server = URI.create(location.getNonPrefixedValue(PREFIX).strip());
        if (server.getScheme() == null || !(server.getScheme().equals("http") || server.getScheme().equals("https")) || server.getHost() == null) {
            throw new IllegalStateException("setu-config: needs an http(s) URL with a host, for example setu-config:http://localhost:8888");
        }
        Binder binder = context.getBinder();
        String application = binder.bind("setu.config.application", String.class)
            .orElseGet(() -> stripServiceSuffix(binder.bind("spring.application.name", String.class).orElse("application")));
        List<String> profiles = binder.bind("spring.profiles.active", Bindable.listOf(String.class)).orElse(List.of());
        String token = binder.bind("setu.config.token", String.class).orElse("");
        return List.of(new SetuConfigDataResource(server, application, profiles, token, location.isOptional()));
    }

    static String stripServiceSuffix(String name) {
        return name.endsWith("-service") ? name.substring(0, name.length() - "-service".length()) : name;
    }
}
