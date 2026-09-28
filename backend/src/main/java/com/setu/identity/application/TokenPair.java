package com.setu.identity.application;

import java.time.Instant;
import java.util.UUID;

public record TokenPair(String accessToken, String refreshToken, Instant refreshTokenExpiresAt, UUID sessionId) {
}
