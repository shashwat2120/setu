package com.setu.identity.infrastructure;

import com.setu.identity.domain.WebAuthnChallenge;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface WebAuthnChallengeRepository extends JpaRepository<WebAuthnChallenge, UUID> {
}
