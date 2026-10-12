package com.setu.identity.infrastructure;

import com.setu.identity.domain.PasswordResetToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, UUID> {

    Optional<PasswordResetToken> findByTokenHash(String tokenHash);

    /** Every reset link the user was sent that has not been used yet (some may already be expired). */
    List<PasswordResetToken> findByUserIdAndUsedAtIsNull(UUID userId);
}
