package com.setu.career.application;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

/**
 * Where uploaded resume files are kept: outside the database, under the SHA-256 of their content, within the college they belong to. A file never changes once stored
 * (its name is its hash), so whatever points at it keeps pointing at the same bytes.
 */
public interface ResumeFileStore {

    /** Stores the bytes under their hash; storing the same content again changes nothing. */
    void put(UUID tenantId, String sha256, byte[] bytes) throws IOException;

    /** The stored bytes, or empty if there is no such file in that college. */
    Optional<byte[]> get(UUID tenantId, String sha256) throws IOException;
}
