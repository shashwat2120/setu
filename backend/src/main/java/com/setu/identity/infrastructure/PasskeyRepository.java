package com.setu.identity.infrastructure;

import com.setu.identity.domain.Passkey;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PasskeyRepository extends JpaRepository<Passkey, UUID> {

    Optional<Passkey> findByCredentialId(byte[] credentialId);

    List<Passkey> findByUserId(UUID userId);
}
