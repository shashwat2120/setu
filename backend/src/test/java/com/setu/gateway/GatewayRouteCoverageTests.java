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
        Map.entry("learning", "learning"), Map.entry("assessment", "assessment"), Map.entry("attendance", "attendance"));
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
    void noRouteIsDeadEveryNonFallbackPatternHasAnEndpointBehindIt() {
        GatewayProperties properties = Binder.get(environment).bind("app.gateway", GatewayProperties.class).get();
        Set<String> patterns = new TreeSet<>();
        handlerMapping.getHandlerMethods().keySet().forEach(info -> patterns.addAll(info.getPathPatternsCondition().getPatternValues()));
        RouteTable routes = routes();
        Set<String> dead = new TreeSet<>();
        for (GatewayProperties.RouteProperties route : properties.routes()) {
            for (String path : route.paths()) {
                if (path.equals("/**")) {
                    continue;
                }
                RouteTable single = new RouteTable(java.util.List.of(new GatewayProperties.RouteProperties(route.id(), route.service(), java.util.List.of(path), java.util.List.of(), null)));
                if (patterns.stream().noneMatch(p -> single.match(p).isPresent())) {
                    dead.add(route.id() + ": " + path);
                }
            }
        }
        assertThat(dead).as("route patterns that no endpoint matches").isEmpty();
        assertThat(routes.match("/definitely/not/there")).as("the fallback route catches anything else").isPresent();
    }

    @Test
    void onlySignInAndKeyPublicationAreOpenAtTheGateway() {
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
            p -> assertThat(p.startsWith("/auth/") || p.equals("/.well-known/jwks.json")).as(p).isTrue());
        assertThat(open).contains("/auth/login", "/.well-known/jwks.json");
    }
}
