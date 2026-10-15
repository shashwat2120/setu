package com.setu.shared.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Clock;
import java.time.Duration;

/** Wires the Redis revocation list for whichever service is running: Identity writes to it, every other service reads it. */
@Configuration(proxyBeanMethods = false)
public class LoginRevocationsConfig {

    @Bean
    LoginRevocations loginRevocations(StringRedisTemplate redis,
                                      @Value("${app.security.jwt.access-token-ttl-minutes:15}") long accessTokenTtlMinutes) {
        return new RedisLoginRevocations(redis, Duration.ofMinutes(accessTokenTtlMinutes), Clock.systemUTC());
    }
}
