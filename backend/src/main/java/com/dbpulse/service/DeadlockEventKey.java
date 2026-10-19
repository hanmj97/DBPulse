package com.dbpulse.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Stable identity across repeated XEL file polling, without storing the file path. */
public final class DeadlockEventKey {
    private DeadlockEventKey() {}

    public static String fromLocation(String fileName, long offset) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            byte[] material = (fileName + "\u0000" + offset).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(digest.digest(material));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
