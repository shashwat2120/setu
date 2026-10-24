package com.setu.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The gateway's route table must send every endpoint the platform has to the service that owns its module. The endpoints are read from the
 * running application's own handler mappings, so adding a controller, moving a module into its own service, or editing the route table
 * without the other fails here instead of in production as a request that quietly reaches the wrong service.
 */
@SpringBootTest
class GatewayRouteCoverageTests {

    /** module (first package under com.setu) -> the service that owns it today. Extend this and the route table together. */
    private static final Map<String, String> SERVICE_OF_MODULE = Map.ofEntries(
        Map.entry("identity", "identity"),
        Map.entry("institution", "academic"), Map.entry("catalog", "academic"), Map.entry("enrollment", "academic"),
        Map.entry("directory", "academic"), Map.entry("guardianaccess", "academic"),
        Map.entry("learning", "learning"), Map.entry("assessment", "assessment"), Map.entry("attendance", "attendance"),
        Map.entry("notification", "notification"), Map.entry("aiexplain", "ai"));
    private static final String REMAINING = "core";

    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping handlerMapping;
    @Autowired
    Environment environment;

    private RouteTable routes() {
        GatewayProperties properties = Binder.get(environment).bind("app.gateway", GatewayProperties.class).get();
        return new RouteTable(properties.routes());
    }

    private static String moduleOf(HandlerMethod handler) {
        String className = handler.getBeanType().getName();
        String[] parts = className.split("\\.");
        return parts.length > 2 && className.startsWith("com.setu.") ? parts[2] : null;
    }

    @Test
    void theEndpointsThatCostSomethingOutsideThePlatformOrTakeALargeBodyHaveACounterOfTheirOwn() {
        RouteTable routes = routes();
        var expected = Map.of(
            "/finance/payments", Map.entry("payment-create", 30),
            "/finance/payments/0b5ae0a1-5b40-4ec7-a2ef-8d7c0f0d1111/checkout", Map.entry("payment-checkout", 10),
            "/finance/payments/0b5ae0a1-5b40-4ec7-a2ef-8d7c0f0d1111/reconcile", Map.entry("payment-reconcile", 30),
            "/career/resumes/file", Map.entry("resume-file", 20));
        expected.forEach((path, route) -> {
            var match = routes.match(path).orElseThrow();
            assertThat(match.route().id()).as(path).isEqualTo(route.getKey());
            assertThat(match.route().ratePerMinute()).as(path + " limit").isEqualTo(route.getValue());
            assertThat(match.route().service()).as(path + " service").isEqualTo("core");
            assertThat(match.isPublic()).as(path + " needs a signed-in caller").isFalse();
        });
        // what the Finance page does for one payment must fit: one create, one checkout, and up to five confirmations
        assertThat(routes.match("/finance/payments/0b5ae0a1-5b40-4ec7-a2ef-8d7c0f0d1111/reconcile").orElseThrow().route().ratePerMinute()).isGreaterThanOrEqualTo(5 * 3);
        // everything around them keeps the general route and its general limit
        for (String path : new String[] {"/finance/payments/0b5ae0a1-5b40-4ec7-a2ef-8d7c0f0d1111", "/finance/payments/0b5ae0a1-5b40-4ec7-a2ef-8d7c0f0d1111/refund",
            "/career/resumes", "/career/resumes/file/extra", "/finance/charges"}) {
            assertThat(routes.match(path).orElseThrow().route().id()).as(path).isEqualTo("core");
        }
        // the provider's webhook is untouched: no limit of its own, and still open at the gateway
        var webhook = routes.match("/finance/provider-webhooks/razorpay").orElseThrow();
        assertThat(webhook.route().id()).isEqualTo("core");
        assertThat(webhook.isPublic()).isTrue();
    }

    @Test
    void everyEndpointOfEveryModuleReachesTheServiceThatOwnsThatModule() {
        RouteTable routes = routes();
        Set<String> wrong = new TreeSet<>();
        int checked = 0;
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : handlerMapping.getHandlerMethods().entrySet()) {
            String module = moduleOf(entry.getValue());
            if (module == null) {
                continue;   // framework controllers (error page, actuator, API docs)
            }
            String expected = SERVICE_OF_MODULE.getOrDefault(module, REMAINING);
            for (String pattern : entry.getKey().getPathPatternsCondition().getPatternValues()) {
                if (pattern.startsWith("/internal/")) {
                    continue;   // service-to-service only: the gateway refuses these by design (internalEndpointsAreNeverRoutable)
                }
                checked++;
                String actual = routes.match(pattern).map(m -> m.route().service()).orElse("NO ROUTE");
                if (!expected.equals(actual)) {
                    wrong.add(pattern + "  (" + entry.getValue().getBeanType().getSimpleName() + ", module " + module + ")  expected " + expected + " but routed to " + actual);
                }
            }
        }
        assertThat(checked).as("the application exposes endpoints").isGreaterThan(100);
        assertThat(wrong).as("endpoints the gateway would send to the wrong service").isEmpty();
    }

    @Test
    void internalEndpointsAreExactlyTheOnesUnderTheInternalPrefix() {
        // The gateway answers 404 for /internal/** whatever the routes say (GatewayFilterTests), so an endpoint that is meant for other services
        // must live there, and nothing else may: an ordinary endpoint under /internal/ would silently become unreachable from outside.
        Set<String> misplaced = new TreeSet<>();
        int internal = 0;
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : handlerMapping.getHandlerMethods().entrySet()) {
            if (moduleOf(entry.getValue()) == null) {
                continue;
            }
            boolean internalController = entry.getValue().getBeanType().getSimpleName().startsWith("Internal");
            for (String pattern : entry.getKey().getPathPatternsCondition().getPatternValues()) {
                boolean internalPath = pattern.startsWith("/internal/");
                internal += internalPath ? 1 : 0;
                if (internalController != internalPath) {
                    misplaced.add(pattern + " (" + entry.getValue().getBeanType().getSimpleName() + ")");
                }
            }
        }
        assertThat(internal).as("the service-to-service endpoints exist").isGreaterThanOrEqualTo(1);
        assertThat(misplaced).as("Internal* controllers map only /internal/**, and only they do").isEmpty();
    }

    @Test
    void noRouteIsDeadEveryNonFallbackPatternHasAnEndpointBehindIt() {
        GatewayProperties properties = Binder.get(environment).bind("app.gateway", GatewayProperties.class).get();
        Set<String> patterns = new TreeSet<>();
        handlerMapping.getHandlerMethods().keySet().forEach(info -> patterns.addAll(info.getPathPatternsCondition().getPatternValues()));
        RouteTable routes = routes();
        Set<String> dead = new TreeSet<>();
        for (GatewayProperties.RouteProperties route : properties.routes()) {
            if (route.websocket()) {
                continue;   // a live route fronts a STOMP endpoint, not a controller: liveRouteFrontsTheStompEndpointTheServicesExpose covers it
            }
            for (String path : route.paths()) {
                if (path.equals("/**")) {
                    continue;
                }
                RouteTable single = new RouteTable(java.util.List.of(new GatewayProperties.RouteProperties(route.id(), route.service(), java.util.List.of(path), java.util.List.of(), null, false, null)));
                if (patterns.stream().noneMatch(p -> single.match(p).isPresent())) {
                    dead.add(route.id() + ": " + path);
                }
            }
        }
        assertThat(dead).as("route patterns that no endpoint matches").isEmpty();
        assertThat(routes.match("/definitely/not/there")).as("the fallback route catches anything else").isPresent();
    }

    @Test
    void onlySignInKeyPublicationAndTheProvidersSignedWebhookAreOpenAtTheGateway() {
        RouteTable routes = routes();
        Set<String> open = new TreeSet<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : handlerMapping.getHandlerMethods().entrySet()) {
            if (moduleOf(entry.getValue()) == null) {
                continue;
            }
            for (String pattern : entry.getKey().getPathPatternsCondition().getPatternValues()) {
                if (routes.match(pattern).map(RouteTable.Match::isPublic).orElse(false)) {
                    open.add(pattern);
                }
            }
        }
        assertThat(open).as("endpoints the gateway lets through without a token").allSatisfy(
            p -> assertThat(p.startsWith("/auth/") || p.equals("/.well-known/jwks.json")
            // the payment provider has no user token; the endpoint itself verifies the provider's HMAC signature (PaymentProviderService)
            || p.equals("/finance/provider-webhooks/razorpay")).as(p).isTrue());
        assertThat(open).contains("/auth/login", "/.well-known/jwks.json", "/finance/provider-webhooks/razorpay");
    }

    /** The STOMP endpoint every live service registers (shared.websocket.WebSocketConfig). */
    private static final String STOMP_ENDPOINT = "/ws";

    @Test
    void liveRouteFrontsTheStompEndpointTheServicesExpose() {
        GatewayProperties properties = Binder.get(environment).bind("app.gateway", GatewayProperties.class).get();
        RouteTable routes = routes();

        RouteTable.Match live = routes.match(STOMP_ENDPOINT).orElseThrow();
        assertThat(live.route().websocket()).as("the STOMP endpoint is on a route that relays live connections").isTrue();
        assertThat(live.route().service()).as("the live attendance board is the Attendance service's").isEqualTo("attendance");
        assertThat(live.isPublic()).as("the handshake carries no token; the STOMP CONNECT frame does, and the service verifies it").isTrue();
        assertThat(routes.match(STOMP_ENDPOINT + "/info")).hasValueSatisfying(m -> assertThat(m.route().websocket()).isTrue());

        // nothing else may relay live connections, and no live route may reach beyond the endpoint: a wildcard here would carry other paths unvetted
        for (GatewayProperties.RouteProperties route : properties.routes()) {
            if (route.websocket()) {
                assertThat(route.paths()).as(route.id()).allSatisfy(p -> assertThat(p).startsWith(STOMP_ENDPOINT));
                assertThat(route.ratePerMinute()).as(route.id() + " limits connection attempts").isNotNull();
            }
        }
    }
}
