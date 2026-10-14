package com.setu.audit;

import com.setu.identity.infrastructure.security.JwtService;
import com.setu.testsupport.Day7TestFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ValueConstants;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.lang.reflect.Parameter;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two sweeps over every HTTP endpoint the application actually maps (read from Spring's handler mapping, so a new controller is
 * covered the day it is added, not the day someone remembers to write a test for it):
 * <ol>
 *   <li><b>Authentication:</b> every endpoint refuses an anonymous caller with 401, except the short list of endpoints that are public
 *       by design (sign-in, health, API docs, the WebSocket handshake whose real check is the STOMP CONNECT frame).</li>
 *   <li><b>Tenant escape:</b> canonical spec §3, "never trust arbitrary collegeId from React". Endpoints that take a {@code tenantId}
 *       from the client treat it as a claim to authorize, not as a fact: a college administrator naming another college's id is
 *       refused (or finds nothing) on every such endpoint, never served.</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
class EndpointSecurityAuditTests {

    /** Public by design; each entry is a path prefix and the reason it is allowed. */
    private static final Map<String, String> PUBLIC_PREFIXES = Map.of(
        "/auth/", "sign-in, token refresh and password reset cannot require a token (step-up is the exception, checked below)",
        "/actuator/health", "liveness/readiness probes",
        "/actuator/info", "build info only",
        "/v3/api-docs", "endpoint shapes only, no data",
        "/swagger-ui", "endpoint shapes only, no data",
        "/ws", "WebSocket handshake; STOMP CONNECT authenticates (AttendanceStompAuthorizationTests)",
        "/error", "container error page");

    @Autowired
    MockMvc mockMvc;
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping handlerMapping;
    @Autowired
    JwtService jwtService;
    @Autowired
    Day7TestFixtures fixtures;

    private record Endpoint(HttpMethod method, String pattern, HandlerMethod handler) {
        String describe() {
            return method + " " + pattern;
        }
    }

    private List<Endpoint> endpoints() {
        List<Endpoint> out = new ArrayList<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> e : handlerMapping.getHandlerMethods().entrySet()) {
            Set<String> patterns = e.getKey().getPathPatternsCondition() == null ? Set.of()
                : e.getKey().getPathPatternsCondition().getPatternValues();
            var methods = e.getKey().getMethodsCondition().getMethods();
            for (String pattern : patterns) {
                if (methods.isEmpty()) {
                    out.add(new Endpoint(HttpMethod.GET, pattern, e.getValue()));
                }
                methods.forEach(m -> out.add(new Endpoint(HttpMethod.valueOf(m.name()), pattern, e.getValue())));
            }
        }
        return out;
    }

    private static String concretePath(String pattern) {
        return pattern.replaceAll("\\{[^}/]+}", UUID.randomUUID().toString()).replace("/**", "/x").replace("*", "x");
    }

    private static boolean isPublic(Endpoint e) {
        return PUBLIC_PREFIXES.keySet().stream().anyMatch(p -> e.pattern.startsWith(p)) && !e.pattern.startsWith("/auth/step-up");
    }

    private static MockHttpServletRequestBuilder request(Endpoint e, String path) {
        var builder = MockMvcRequestBuilders.request(e.method, path);
        if (e.method == HttpMethod.POST || e.method == HttpMethod.PUT || e.method == HttpMethod.PATCH) {
            builder.contentType(MediaType.APPLICATION_JSON).content("{}");
        }
        return builder;
    }

    @Test
    void everyNonPublicEndpointRefusesAnAnonymousCaller() throws Exception {
        List<Endpoint> all = endpoints();
        List<String> notRefused = new ArrayList<>();
        int checked = 0;
        for (Endpoint e : all) {
            if (isPublic(e)) {
                continue;
            }
            checked++;
            int status = mockMvc.perform(request(e, concretePath(e.pattern))).andReturn().getResponse().getStatus();
            if (status != 401) {
                notRefused.add(e.describe() + " answered " + status);
            }
        }

        assertThat(checked).as("the sweep must actually cover the application's endpoints").isGreaterThan(150);
        assertThat(notRefused).as("endpoints that did not answer an anonymous caller with 401").isEmpty();
    }

    @Test
    void theStepUpEndpointsNeedASignedInCaller() throws Exception {
        for (Endpoint e : endpoints()) {
            if (e.pattern.startsWith("/auth/step-up")) {
                int status = mockMvc.perform(request(e, concretePath(e.pattern))).andReturn().getResponse().getStatus();
                assertThat(status).as(e.describe()).isEqualTo(401);
            }
        }
    }

    @Test
    void aCollegeAdminNamingAnotherCollegesIdIsNeverServedOnAnyEndpointThatTakesATenantId() throws Exception {
        var mine = fixtures.provisionTenant("SECA");
        var theirs = fixtures.provisionTenant("SECB");
        String bearer = "Bearer " + jwtService.issueAccessToken(mine.adminUserId());

        Map<String, Integer> conclusive = new TreeMap<>();
        List<String> leaked = new ArrayList<>();
        List<String> inconclusive = new ArrayList<>();
        for (Endpoint e : endpoints()) {
            if (e.method != HttpMethod.GET || !takesRequiredTenantIdParam(e.handler)) {
                continue;
            }
            var builder = request(e, concretePath(e.pattern)).header("Authorization", bearer);
            boolean buildable = true;
            for (Parameter p : e.handler.getMethod().getParameters()) {
                RequestParam rp = p.getAnnotation(RequestParam.class);
                if (rp == null) {
                    continue;
                }
                String name = rp.name().isEmpty() && rp.value().isEmpty() ? p.getName() : (rp.name().isEmpty() ? rp.value() : rp.name());
                if (name.equals("tenantId")) {
                    builder.param("tenantId", theirs.tenantId().toString());
                } else if (rp.required() && rp.defaultValue().equals(ValueConstants.DEFAULT_NONE)) {
                    String value = sample(p.getType());
                    if (value == null) {
                        buildable = false;
                    } else {
                        builder.param(name, value);
                    }
                }
            }
            if (!buildable) {
                inconclusive.add(e.describe() + " (parameter type cannot be filled in generically)");
                continue;
            }
            int status = mockMvc.perform(builder).andReturn().getResponse().getStatus();
            if (status == 403 || status == 404) {
                conclusive.put(e.describe(), status);
            } else if (status == 400) {
                inconclusive.add(e.describe() + " answered 400 to the generic request");
            } else {
                leaked.add(e.describe() + " answered " + status + " for another college's id");
            }
        }

        assertThat(leaked).as("endpoints that served, or failed on, another college's tenant id").isEmpty();
        assertThat(conclusive).as("endpoints proven to refuse a foreign tenant id (inconclusive: " + inconclusive + ")")
            .hasSizeGreaterThanOrEqualTo(10);
    }

    private static boolean takesRequiredTenantIdParam(HandlerMethod handler) {
        for (Parameter p : handler.getMethod().getParameters()) {
            RequestParam rp = p.getAnnotation(RequestParam.class);
            if (rp != null && p.getType() == UUID.class && "tenantId".equals(p.getName()) && rp.required()) {
                return true;
            }
        }
        return false;
    }

    private static String sample(Class<?> type) {
        if (type == UUID.class) {
            return UUID.randomUUID().toString();
        }
        if (type == LocalDate.class) {
            return "2025-01-01";
        }
        if (type == String.class) {
            return "x";
        }
        if (type == int.class || type == Integer.class || type == long.class || type == Long.class) {
            return "1";
        }
        if (type == boolean.class || type == Boolean.class) {
            return "false";
        }
        return null;
    }
}
