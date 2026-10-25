package com.guodi.assistant.versioning;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class Fingerprints {
    private Fingerprints() {}

    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("JDK does not provide SHA-256", error);
        }
    }

    public static byte[] classBytes(Class<?> type) {
        String resource = "/" + type.getName().replace('.', '/') + ".class";
        try (var input = type.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IllegalStateException("Cannot fingerprint component class: " + type.getName());
            }
            return input.readAllBytes();
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }
}
