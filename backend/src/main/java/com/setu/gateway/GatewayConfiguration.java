package com.setu.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.setu.shared.security.RemoteTokenAuthenticator;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.net.URI;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Wires the gateway's parts together. Active only when {@code setu.service=gateway}. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "setu.service", havingValue = "gateway")
@EnableConfigurationProperties(GatewayProperties.class)
public class GatewayConfiguration {

    @Bean
    RouteTable routeTable(GatewayProperties properties) {
        return new RouteTable(properties.routes() == null ? List.of() : properties.routes());
    }

    @Bean
    ServiceDirectory serviceDirectory(StringRedisTemplate redis, GatewayProperties properties) {
        Map<String, List<URI>> configured = new LinkedHashMap<>();
        properties.services().forEach((name, service) -> configured.put(name, service.instances()));
        return new RedisServiceDirectory(redis, configured, properties.upstream().quarantine(), Clock.systemUTC());
    }

    @Bean
    GatewayRateLimiter gatewayRateLimiter(StringRedisTemplate redis) {
        return new GatewayRateLimiter(redis);
    }

    @Bean
    Upstream upstream(GatewayProperties properties) {
        return new ApacheUpstream(properties.upstream().connectTimeout(), properties.upstream().responseTimeout());
    }

    @Bean
    FilterRegistrationBean<GatewayFilter> gatewayFilter(RouteTable routes, RemoteTokenAuthenticator authenticator,
                                                         GatewayRateLimiter rateLimiter, ServiceDirectory directory, Upstream upstream,
                                                         ObjectMapper objectMapper, GatewayProperties properties, MeterRegistry meters) {
        FilterRegistrationBean<GatewayFilter> registration = new FilterRegistrationBean<>(
            new GatewayFilter(routes, authenticator, rateLimiter, directory, upstream, objectMapper, properties, meters));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);   // right after the correlation id filter
        registration.addUrlPatterns("/*");
        return registration;
    }
}
