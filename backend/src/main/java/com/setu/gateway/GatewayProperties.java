package com.setu.gateway;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * The gateway's whole configuration: which services exist and where, which paths go to which service, which paths need no token, and the
 * limits. Nothing about a route is in code, so moving a module into its own service is a configuration change plus a test
 * ({@code GatewayRouteCoverageTests} fails if a controller's path would reach the wrong service).
 */
@ConfigurationProperties("app.gateway")
public record GatewayProperties(
    /** Service name -> where it can be reached when discovery knows of no live instance (and always, as a floor). */
    @DefaultValue Map<String, ServiceProperties> services,
    List<RouteProperties> routes,
    @DefaultValue CorsProperties cors,
    @DefaultValue UpstreamProperties upstream,
    @DefaultValue LimitProperties limits) {

    public record ServiceProperties(@DefaultValue List<URI> instances) {
    }

    /**
     * @param paths      Ant-style path patterns this route owns; the most specific matching pattern across all routes wins
     * @param publicPaths the subset of requests the gateway lets through without a token (the service itself still enforces what it must,
     *                   for example the step-up endpoints under {@code /auth})
     * @param ratePerMinute overrides the default request limit for callers of this route
     * @param websocket  true when this route's paths carry live connections: the gateway then relays a WebSocket upgrade to the owning service
     *                   (see {@link WebSocketRelayHandler}); on any other route an upgrade is refused with 501
     * @param responseTimeout how long the service may take to answer on this route; unset means {@code app.gateway.upstream.response-timeout}
     */
    public record RouteProperties(String id, String service, List<String> paths, @DefaultValue List<String> publicPaths, Integer ratePerMinute,
                                  @DefaultValue("false") boolean websocket, Duration responseTimeout) {
    }

    public record CorsProperties(@DefaultValue List<String> allowedOrigins) {
    }

    public record UpstreamProperties(
        @DefaultValue("2s") Duration connectTimeout,
        @DefaultValue("30s") Duration responseTimeout,
        /** How long an instance that refused or dropped a connection is left out before it is tried again. */
        @DefaultValue("15s") Duration quarantine) {
    }

    public record LimitProperties(
        /** Per client address, for requests that carry no valid token. */
        @DefaultValue("120") int anonymousPerMinute,
        /** Per signed-in user. */
        @DefaultValue("600") int userPerMinute) {
    }
}
