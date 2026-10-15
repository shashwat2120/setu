package com.setu.shared.config;

import org.springframework.boot.context.config.ConfigDataResource;

import java.net.URI;
import java.util.List;
import java.util.Objects;

/**
 * One configuration to fetch from the Setu config server: where, for which application and profiles, and with which token. The token takes
 * no part in equality (two imports of the same configuration are the same import) and is never printed.
 */
public class SetuConfigDataResource extends ConfigDataResource {

    private final URI server;
    private final String application;
    private final List<String> profiles;
    private final String token;

    SetuConfigDataResource(URI server, String application, List<String> profiles, String token, boolean optional) {
        super(optional);
        this.server = server;
        this.application = application;
        this.profiles = List.copyOf(profiles);
        this.token = token;
    }

    URI server() {
        return server;
    }

    String application() {
        return application;
    }

    List<String> profiles() {
        return profiles;
    }

    String token() {
        return token;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof SetuConfigDataResource other
            && server.equals(other.server) && application.equals(other.application) && profiles.equals(other.profiles);
    }

    @Override
    public int hashCode() {
        return Objects.hash(server, application, profiles);
    }

    @Override
    public String toString() {
        return "setu-config:" + server + " (" + application + ", profiles " + profiles + ")";
    }
}
