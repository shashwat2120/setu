package com.setu.notification.infrastructure.websocket;

import org.springframework.context.annotation.Configuration;
import org.jspecify.annotations.NonNull;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * Generic STOMP-over-WebSocket transport — {@code notification}'s
 * documented ownership of realtime WebSocket fan-out (see this module's
 * package-info). Deliberately knows nothing about attendance, sections, or
 * any other business destination — that's
 * {@link StompAuthorizationChannelInterceptor}'s job, wired in generically
 * below via {@code interceptors(...)}. Origin checking is relaxed
 * ({@code setAllowedOriginPatterns("*")}) because the actual security
 * boundary here is the CONNECT-frame bearer-token check the interceptor
 * performs, not same-origin policy — the same reasoning the REST API's
 * stateless bearer-token design already applies (see identity's
 * SecurityConfig).
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final StompAuthorizationChannelInterceptor authorizationChannelInterceptor;

    public WebSocketConfig(StompAuthorizationChannelInterceptor authorizationChannelInterceptor) {
        this.authorizationChannelInterceptor = authorizationChannelInterceptor;
    }

    @Override
    public void registerStompEndpoints(@NonNull StompEndpointRegistry registry) {
        registry.addEndpoint("/ws").setAllowedOriginPatterns("*");
    }

    @Override
    public void configureMessageBroker(@NonNull MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic");
    }

    @Override
    public void configureClientInboundChannel(@NonNull ChannelRegistration registration) {
        registration.interceptors(authorizationChannelInterceptor);
    }
}
