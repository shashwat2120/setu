package com.setu.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.setu.shared.security.AccessTokenClaims;
import com.setu.shared.security.RemoteTokenAuthenticator;
import com.setu.shared.web.CorrelationIdFilter;
import com.setu.shared.web.ErrorResponse;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The edge of the platform. Every request that is not the gateway's own actuator goes through, in order:
 * <ol>
 *   <li><b>CORS</b>: the gateway is the only place cross-origin rules live; a preflight is answered here and never forwarded, and any
 *       {@code Access-Control-*} header a service sends is dropped so there is one authority.</li>
 *   <li><b>Route</b>: the owning service from the route table; no route is a 404 in the standard error envelope.</li>
 *   <li><b>Authentication</b>: unless the path is public, the bearer token is verified against Identity's published keys and the revocation
 *       list ({@link RemoteTokenAuthenticator}); a bad, expired, forged or revoked token is refused here, before it costs a service anything.
 *       The original {@code Authorization} header is still forwarded, because every service verifies for itself: the gateway is not a trust
 *       boundary the services may lean on.</li>
 *   <li><b>Rate limit</b>: per signed-in user, otherwise per client address, per route.</li>
 *   <li><b>Proxy</b>: to the first reachable instance {@link ServiceDirectory} offers, with honest failures: no instance is 503, nothing
 *       answered is 502, no answer in time is 504, each with the standard envelope and the correlation id. Only idempotent requests are
 *       retried on another instance, and only when no instance answered at all.</li>
 * </ol>
 * Headers a client could use to impersonate the edge ({@code X-Forwarded-*}, {@code Forwarded}) are discarded and set from the real
 * connection. WebSocket upgrades are refused with 501 until live routing is added (6A.5).
 */
public class GatewayFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(GatewayFilter.class);

    /** Headers that describe one connection, not the message, plus the ones the gateway sets itself (RFC 9110 section 7.6.1). */
    private static final Set<String> NOT_FORWARDED = Set.of("connection", "keep-alive", "proxy-authenticate", "proxy-authorization", "te",
        "trailer", "transfer-encoding", "upgrade", "host", "content-length", "x-forwarded-for", "x-forwarded-proto", "x-forwarded-host",
        "x-forwarded-port", "forwarded");
    private static final Set<String> IDEMPOTENT = Set.of("GET", "HEAD", "OPTIONS");

    private final RouteTable routes;
    private final RemoteTokenAuthenticator authenticator;
    private final GatewayRateLimiter rateLimiter;
    private final ServiceDirectory directory;
    private final Upstream upstream;
    private final ObjectMapper objectMapper;
    private final GatewayProperties properties;
    private final MeterRegistry meters;

    public GatewayFilter(RouteTable routes, RemoteTokenAuthenticator authenticator, GatewayRateLimiter rateLimiter,
                         ServiceDirectory directory, Upstream upstream, ObjectMapper objectMapper, GatewayProperties properties,
                         MeterRegistry meters) {
        this.routes = routes;
        this.authenticator = authenticator;
        this.rateLimiter = rateLimiter;
        this.directory = directory;
        this.upstream = upstream;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.meters = meters;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.equals("/actuator") || path.startsWith("/actuator/");   // the gateway's own health and info
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                     @NonNull FilterChain chain) throws ServletException, IOException {
        String origin = request.getHeader("Origin");
        boolean originAllowed = origin != null && properties.cors().allowedOrigins().contains(origin);

        if (isPreflight(request)) {
            if (!originAllowed) {
                count("none", "preflight_refused");
                writeError(request, response, 403, "CORS_ORIGIN_NOT_ALLOWED", "this origin is not allowed");
                return;
            }
            addCors(response, origin);
            response.setHeader("Access-Control-Allow-Methods", "GET, POST, PUT, PATCH, DELETE, OPTIONS");
            response.setHeader("Access-Control-Allow-Headers", "Authorization, Content-Type, X-Correlation-Id");
            response.setHeader("Access-Control-Max-Age", "600");
            response.setStatus(204);
            count("none", "preflight");
            return;
        }
        if (originAllowed) {
            addCors(response, origin);
        }

        if ("websocket".equalsIgnoreCase(request.getHeader("Upgrade"))) {
            count("none", "websocket_unsupported");
            writeError(request, response, 501, "WEBSOCKET_NOT_SUPPORTED", "live connections are not routed through the gateway yet");
            return;
        }

        Optional<RouteTable.Match> match = routes.match(request.getRequestURI());
        if (match.isEmpty()) {
            count("none", "no_route");
            writeError(request, response, 404, "NO_ROUTE", "no route for this path");
            return;
        }
        RouteTable.Route route = match.get().route();

        Optional<AccessTokenClaims> caller = Optional.empty();
        if (!match.get().isPublic()) {
            caller = authenticate(request);
            if (caller.isEmpty()) {
                count(route.id(), "unauthenticated");
                response.setHeader("WWW-Authenticate", "Bearer");
                writeError(request, response, 401, "UNAUTHENTICATED", "authentication is required");
                return;
            }
        }

        int limit = route.ratePerMinute() != null ? route.ratePerMinute()
            : caller.isPresent() ? properties.limits().userPerMinute() : properties.limits().anonymousPerMinute();
        String bucket = caller.map(c -> "user:" + c.userId()).orElse("ip:" + request.getRemoteAddr());
        GatewayRateLimiter.Decision decision = rateLimiter.check(route.id(), bucket, limit);
        if (!decision.allowed()) {
            count(route.id(), "rate_limited");
            response.setHeader("Retry-After", Long.toString(decision.retryAfterSeconds()));
            writeError(request, response, 429, "RATE_LIMITED", "too many requests; try again shortly");
            return;
        }

        proxy(request, response, route);
    }

    private Optional<AccessTokenClaims> authenticate(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            return Optional.empty();
        }
        return authenticator.authenticate(header.substring("Bearer ".length()));
    }

    private void proxy(HttpServletRequest request, HttpServletResponse response, RouteTable.Route route) throws IOException {
        List<URI> candidates = directory.available(route.service());
        if (candidates.isEmpty()) {
            count(route.id(), "unavailable");
            response.setHeader("Retry-After", "5");
            writeError(request, response, 503, "SERVICE_UNAVAILABLE", "the " + route.service() + " service has no reachable instance");
            return;
        }

        boolean mayRetry = IDEMPOTENT.contains(request.getMethod().toUpperCase(Locale.ROOT));
        Upstream.UpstreamException failure = null;
        for (URI instance : candidates) {
            if (failure != null && !mayRetry) {
                break;
            }
            try (Upstream.Response answer = upstream.exchange(buildRequest(request, instance))) {
                copyResponse(answer, response);
                count(route.id(), "proxied");
                return;
            } catch (Upstream.UpstreamException e) {
                failure = e;
                if (e.kind() == Upstream.UpstreamException.Kind.CONNECT) {
                    directory.reportFailure(route.service(), instance);
                    continue;
                }
                break;   // timed out or failed after connecting: the request may have been processed, so never send it again
            } catch (IOException e) {
                // the client went away or the response stream broke after the answer began: nothing can be changed now
                log.debug("gateway: copying the {} response aborted: {}", route.service(), e.toString());
                count(route.id(), "aborted");
                return;
            }
        }

        boolean timedOut = failure != null && failure.kind() == Upstream.UpstreamException.Kind.TIMEOUT;
        log.warn("gateway: {} {} -> {} failed: {}", request.getMethod(), request.getRequestURI(), route.service(),
            failure == null ? "no attempt" : failure.getMessage());
        count(route.id(), timedOut ? "gateway_timeout" : "bad_gateway");
        if (timedOut) {
            writeError(request, response, 504, "GATEWAY_TIMEOUT", "the " + route.service() + " service did not answer in time");
        } else {
            writeError(request, response, 502, "BAD_GATEWAY", "the " + route.service() + " service could not be reached");
        }
    }

    private Upstream.Request buildRequest(HttpServletRequest request, URI instance) throws IOException {
        String base = instance.toString();
        String target = (base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + request.getRequestURI()
            + (request.getQueryString() == null ? "" : "?" + request.getQueryString());

        List<Upstream.Header> headers = new ArrayList<>();
        for (String name : Collections.list(request.getHeaderNames())) {
            if (NOT_FORWARDED.contains(name.toLowerCase(Locale.ROOT)) || name.equalsIgnoreCase(CorrelationIdFilter.HEADER_NAME)) {
                continue;
            }
            for (String value : Collections.list(request.getHeaders(name))) {
                headers.add(new Upstream.Header(name, value));
            }
        }
        headers.add(new Upstream.Header("X-Forwarded-For", request.getRemoteAddr()));
        headers.add(new Upstream.Header("X-Forwarded-Proto", request.getScheme()));
        if (request.getHeader("Host") != null) {
            headers.add(new Upstream.Header("X-Forwarded-Host", request.getHeader("Host")));
        }
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        headers.add(new Upstream.Header(CorrelationIdFilter.HEADER_NAME, correlationId != null ? correlationId : UUID.randomUUID().toString()));

        boolean hasBody = request.getContentLengthLong() > 0 || request.getHeader("Transfer-Encoding") != null;
        InputStream body = hasBody ? request.getInputStream() : null;
        return new Upstream.Request(request.getMethod(), URI.create(target), headers, body, hasBody ? request.getContentLengthLong() : 0);
    }

    private void copyResponse(Upstream.Response answer, HttpServletResponse response) throws IOException {
        response.setStatus(answer.status());
        for (Upstream.Header header : answer.headers()) {
            String name = header.name().toLowerCase(Locale.ROOT);
            if (NOT_FORWARDED.contains(name) && !name.equals("content-length") || name.startsWith("access-control-")
                || name.equals(CorrelationIdFilter.HEADER_NAME.toLowerCase(Locale.ROOT))) {
                continue;
            }
            response.addHeader(header.name(), header.value());
        }
        try (OutputStream out = response.getOutputStream()) {
            answer.body().transferTo(out);
        }
    }

    private static boolean isPreflight(HttpServletRequest request) {
        return "OPTIONS".equalsIgnoreCase(request.getMethod()) && request.getHeader("Origin") != null
            && request.getHeader("Access-Control-Request-Method") != null;
    }

    private static void addCors(HttpServletResponse response, String origin) {
        response.setHeader("Access-Control-Allow-Origin", origin);
        response.addHeader("Vary", "Origin");
        response.setHeader("Access-Control-Allow-Credentials", "true");
        response.setHeader("Access-Control-Expose-Headers", CorrelationIdFilter.HEADER_NAME + ", Retry-After");
    }

    private void writeError(HttpServletRequest request, HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        objectMapper.writeValue(response.getWriter(),
            ErrorResponse.of(status, code, message, request.getRequestURI(), correlationId != null ? correlationId : UUID.randomUUID().toString()));
    }

    private void count(String route, String outcome) {
        meters.counter("setu.gateway.requests", "route", route, "outcome", outcome).increment();
    }
}
