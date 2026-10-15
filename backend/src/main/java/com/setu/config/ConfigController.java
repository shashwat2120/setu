package com.setu.config;

import com.setu.shared.web.CorrelationIdFilter;
import com.setu.shared.web.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@code GET /config/{application}/{profiles}}: the merged configuration for one application, to that application alone. The caller
 * proves who it is with the bearer token configured for that exact application; a token for another application, a wrong token and a
 * missing one are all refused, and an application nobody configured a token for cannot be fetched at all. Responses are never cached and
 * every fetch is logged (application, profiles, caller address, outcome), never with a value.
 */
@RestController
@ConditionalOnProperty(name = "setu.service", havingValue = "config")
public class ConfigController {

    private static final Logger log = LoggerFactory.getLogger(ConfigController.class);

    public record ConfigResponse(String application, List<String> profiles, String version, Map<String, String> properties) {
    }

    private final ConfigAssembler assembler;
    private final Map<String, String> tokens;

    public ConfigController(ConfigAssembler assembler, ConfigServerProperties properties) {
        this.assembler = assembler;
        this.tokens = properties.tokens();
    }

    @GetMapping({"/config/{application}", "/config/{application}/{profiles}"})
    public ResponseEntity<?> fetch(@PathVariable String application, @PathVariable(required = false) String profiles, HttpServletRequest request) {
        String caller = request.getRemoteAddr();
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            log.warn("config: {} asked for {} without a token", caller, safe(application));
            return error(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "a bearer token is required", request);
        }
        String presented = header.substring("Bearer ".length()).strip();
        String expected = tokens.get(application);
        boolean knownToken = tokens.values().stream().anyMatch(t -> sameToken(t, presented));
        if (!knownToken) {
            log.warn("config: {} presented an unknown token for {}", caller, safe(application));
            return error(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "the token is not valid", request);
        }
        if (expected == null || !sameToken(expected, presented)) {
            log.warn("config: {} presented a token that is not {}'s", caller, safe(application));
            return error(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "this token may not read that application's configuration", request);
        }
        List<String> profileList = profiles == null || profiles.isBlank() ? List.of()
            : Arrays.stream(profiles.split(",")).map(String::strip).filter(p -> !p.isEmpty() && !p.equals("default")).toList();
        try {
            ConfigAssembler.Assembled assembled = assembler.assemble(application, profileList);
            log.info("config: served {} profiles={} version={} from {} file(s) to {}", application, profileList, assembled.version(),
                assembled.sources().size(), caller);
            return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new ConfigResponse(assembled.application(), assembled.profiles(), assembled.version(), assembled.properties()));
        } catch (IllegalArgumentException e) {
            return error(HttpStatus.BAD_REQUEST, "INVALID_NAME", "application and profile names are lowercase letters, digits and hyphens", request);
        }
    }

    private static boolean sameToken(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    /** Names come from the URL: keep them out of the log if they are not plain names. */
    private static String safe(String name) {
        return ConfigAssembler.NAME.matcher(name).matches() ? name : "<invalid name>";
    }

    private static ResponseEntity<ErrorResponse> error(HttpStatus status, String code, String message, HttpServletRequest request) {
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
            .body(ErrorResponse.of(status.value(), code, message, request.getRequestURI(), correlationId != null ? correlationId : UUID.randomUUID().toString()));
    }
}
