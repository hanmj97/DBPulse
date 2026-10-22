package com.dbpulse.security;

import com.sun.jna.platform.win32.Crypt32Util;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Optional;

/**
 * Windows user-scope DPAPI. Stores ONLY encrypted blobs under the current
 * Windows user's LOCALAPPDATA. No plaintext password or Windows keys on disk.
 *
 * Must run directly as the intended Windows user; NEVER inside Docker or
 * as LocalSystem. DPAPI decryption is bound to the Windows user profile.
 */
public final class WindowsDpapiCredentialStore implements TargetCredentialStore {
    private static final byte[] HEADER = "DBP1".getBytes(StandardCharsets.US_ASCII);
    private static final int MAX_BLOB_SIZE = 32_768;
    private final Path directory;

    public WindowsDpapiCredentialStore(Path directory) {
        this.directory = directory;
    }

    @Override public boolean isAvailable() { return true; }

    private Path pathFor(String id) {
        if (id == null || !id.matches(
            "[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}")) {
            throw new IllegalArgumentException("INVALID_TARGET_ID");
        }
        return directory.resolve(id.toLowerCase() + ".dpapi");
    }

    @Override public synchronized void save(String id, String password) {
        Path destination = pathFor(id);
        if (password == null || password.isBlank() || password.length() > 512) {
            throw new IllegalArgumentException("INVALID_CREDENTIAL");
        }
        byte[] plain = password.getBytes(StandardCharsets.UTF_8);
        byte[] sealed;
        try {
            sealed = Crypt32Util.cryptProtectData(plain);
        } finally {
            Arrays.fill(plain, (byte) 0);
        }
        Path tmp = null;
        try {
            Files.createDirectories(directory);
            if (Files.isSymbolicLink(directory)) throw new IOException("UNSAFE_CREDENTIAL_DIRECTORY");
            if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(destination)) {
                throw new IOException("UNSAFE_CREDENTIAL_FILE");
            }
            tmp = Files.createTempFile(directory, "dbpulse-credential-", ".tmp");
            byte[] wrapped = new byte[HEADER.length + sealed.length];
            System.arraycopy(HEADER, 0, wrapped, 0, HEADER.length);
            System.arraycopy(sealed, 0, wrapped, HEADER.length, sealed.length);
            Files.write(tmp, wrapped);
            Arrays.fill(wrapped, (byte) 0);
            try {
                Files.move(tmp, destination, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                // Never silently downgrade to a possibly partial credential write.
                throw new IOException("ATOMIC_CREDENTIAL_WRITE_UNAVAILABLE", unsupported);
            }
        } catch (IOException ex) {
            throw new IllegalStateException("CREDENTIAL_STORE_IO_ERROR");
        } finally {
            Arrays.fill(sealed, (byte) 0);
            if (tmp != null) try { Files.deleteIfExists(tmp); } catch (IOException ignored) { }
        }
    }

    @Override public synchronized Optional<String> load(String id) {
        Path file = pathFor(id);
        try {
            if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
            if (Files.isSymbolicLink(file) || Files.size(file) > MAX_BLOB_SIZE) {
                throw new IOException("UNSAFE_CREDENTIAL_FILE");
            }
            byte[] wrapped = Files.readAllBytes(file);
            try {
                if (wrapped.length <= HEADER.length) throw new IOException("INVALID_CREDENTIAL_BLOB");
                for (int i = 0; i < HEADER.length; i++) {
                    if (wrapped[i] != HEADER[i]) throw new IOException("INVALID_CREDENTIAL_VERSION");
                }
                byte[] sealed = Arrays.copyOfRange(wrapped, HEADER.length, wrapped.length);
                try {
                    byte[] plain = Crypt32Util.cryptUnprotectData(sealed);
                    try {
                        return Optional.of(new String(plain, StandardCharsets.UTF_8));
                    } finally {
                        Arrays.fill(plain, (byte) 0);
                    }
                } finally {
                    Arrays.fill(sealed, (byte) 0);
                }
            } finally {
                Arrays.fill(wrapped, (byte) 0);
            }
        } catch (IOException | RuntimeException ex) {
            // DPAPI failure can mean a different Windows account or invalid user profile.
            // Never pass native error details or decrypted bytes through the API/logs.
            throw new IllegalStateException("CREDENTIAL_UNAVAILABLE_OR_CORRUPT");
        }
    }

    @Override public synchronized boolean has(String id) {
        Path file = pathFor(id);
        return Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
            && !Files.isSymbolicLink(file);
    }

    @Override public synchronized void remove(String id) {
        Path file = pathFor(id);
        try {
            if (Files.isSymbolicLink(file)) throw new IOException("UNSAFE_CREDENTIAL_FILE");
            Files.deleteIfExists(file);
        } catch (IOException ex) {
            throw new IllegalStateException("CREDENTIAL_STORE_IO_ERROR");
        }
    }
}
