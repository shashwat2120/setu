package com.setu.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.client.WebSocketClient;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

import java.util.List;

/**
 * Registers {@link WebSocketRelayHandler} on every path of every route marked {@code websocket}. The origin rule is the gateway's own CORS list,
 * the single place cross-origin rules live; {@link GatewayFilter} has already routed, authenticated and rate limited the upgrade by the time
 * this mapping sees it. Active only when {@code setu.service=gateway}.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "setu.service", havingValue = "gateway")
@EnableWebSocket
public class GatewayWebSocketConfiguration implements WebSocketConfigurer {

    private final GatewayProperties properties;
    private final WebSocketRelayHandler relay;

    public GatewayWebSocketConfiguration(GatewayProperties properties, WebSocketRelayHandler relay) {
        this.properties = properties;
        this.relay = relay;
    }

    @Bean
    static WebSocketClient gatewayWebSocketClient() {
        return new StandardWebSocketClient();
    }

    @Bean
    static WebSocketRelayHandler webSocketRelayHandler(ServiceDirectory directory, WebSocketClient gatewayWebSocketClient, ObjectMapper objectMapper,
                                                       GatewayProperties properties, MeterRegistry meters) {
        return new WebSocketRelayHandler(directory, gatewayWebSocketClient, objectMapper, properties.upstream().connectTimeout(), meters);
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        String[] paths = (properties.routes() == null ? List.<GatewayProperties.RouteProperties>of() : properties.routes()).stream()
            .filter(GatewayProperties.RouteProperties::websocket)
            .flatMap(route -> route.paths().stream())
            .toArray(String[]::new);
        if (paths.length == 0) {
            return;   // no live route configured: the filter refuses every upgrade with 501, and nothing is mapped
        }
        registry.addHandler(relay, paths)
            .addInterceptors(relay.handshake())
            .setAllowedOrigins(properties.cors().allowedOrigins().toArray(String[]::new));
    }
}
