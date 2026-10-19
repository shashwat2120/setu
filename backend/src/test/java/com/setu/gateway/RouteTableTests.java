package com.setu.gateway;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RouteTableTests {

    private static GatewayProperties.RouteProperties route(String id, String service, List<String> paths, List<String> publicPaths) {
        return new GatewayProperties.RouteProperties(id, service, paths, publicPaths, null, false, null);
    }

    private final RouteTable table = new RouteTable(List.of(
        route("core", "core", List.of("/**"), List.of("/swagger-ui/**")),
        route("identity", "identity", List.of("/auth/**", "/identity/**", "/.well-known/**"), List.of("/auth/**", "/.well-known/jwks.json")),
        route("academic", "academic", List.of("/institution/**", "/catalog/**"), List.of())));

    @Test
    void theMostSpecificPatternWinsRegardlessOfTheOrderRoutesAreWritten() {
        assertThat(table.match("/institution/colleges").orElseThrow().route().id()).isEqualTo("academic");
        assertThat(table.match("/auth/login").orElseThrow().route().id()).isEqualTo("identity");
        assertThat(table.match("/learning/modules").orElseThrow().route().id()).isEqualTo("core");
        assertThat(table.match("/").orElseThrow().route().id()).isEqualTo("core");
    }

    @Test
    void publicPathsAreRouteSpecificAndPatternBased() {
        assertThat(table.match("/auth/login").orElseThrow().isPublic()).isTrue();
        assertThat(table.match("/auth/step-up/start").orElseThrow().isPublic()).as("public at the gateway, enforced by Identity").isTrue();
        assertThat(table.match("/.well-known/jwks.json").orElseThrow().isPublic()).isTrue();
        assertThat(table.match("/identity/me").orElseThrow().isPublic()).isFalse();
        assertThat(table.match("/institution/colleges").orElseThrow().isPublic()).isFalse();
        assertThat(table.match("/swagger-ui/index.html").orElseThrow().isPublic()).isTrue();
        assertThat(table.match("/learning/modules").orElseThrow().isPublic()).isFalse();
    }

    @Test
    void aPathTraversalLookalikeDoesNotEscapeItsRoute() {
        // The path is matched as the servlet container normalized it; a "/institution/../auth/login" never reaches here un-normalized,
        // but a literal segment that merely resembles a prefix must not match it.
        assertThat(table.match("/institutions/colleges").orElseThrow().route().id()).isEqualTo("core");
        assertThat(table.match("/authx/login").orElseThrow().route().id()).isEqualTo("core");
    }

    @Test
    void noRoutesMeansNoMatch() {
        assertThat(new RouteTable(List.of()).match("/anything")).isEmpty();
    }
}
