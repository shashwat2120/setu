package com.setu.career.infrastructure;

import com.setu.career.application.ResumeFileStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Keeps resume files in a directory (a volume in the containers, {@code app.resumes.dir}) as {@code <college>/<sha256>.pdf}. The college is a UUID and the name a 64-digit
 * hash that is checked before it is used, so no value from a request can name a path outside the directory. A file is written to a temporary name and moved into place, so a
 * reader never sees half of one.
 */
@Component
class FileSystemResumeFileStore implements ResumeFileStore {

    private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");

    private final Path root;

    FileSystemResumeFileStore(@Value("${app.resumes.dir:${java.io.tmpdir}/setu-resumes}") String dir) {
        this.root = Path.of(dir).toAbsolutePath().normalize();
    }

    @Override
    public void put(UUID tenantId, String sha256, byte[] bytes) throws IOException {
        Path target = pathOf(tenantId, sha256);
        if (Files.exists(target)) {
            return;
        }
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), "upload-", ".part");
        try {
            Files.write(temporary, bytes);
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    @Override
    public Optional<byte[]> get(UUID tenantId, String sha256) throws IOException {
        Path file = pathOf(tenantId, sha256);
        return Files.isRegularFile(file) ? Optional.of(Files.readAllBytes(file)) : Optional.empty();
    }

    private Path pathOf(UUID tenantId, String sha256) {
        if (tenantId == null || sha256 == null || !HASH.matcher(sha256).matches()) {
            throw new IllegalArgumentException("not a stored resume file");
        }
        Path path = root.resolve(tenantId.toString()).resolve(sha256 + ".pdf").normalize();
        if (!path.startsWith(root)) {
            throw new IllegalArgumentException("not a stored resume file");
        }
        return path;
    }
}
