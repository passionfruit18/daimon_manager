package com.daimon.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.regex.Pattern;

/** SHA-256 helpers shared by snapshots and serialization commits. */
public final class Hashes {

    private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-f]{64}");
    public static final int SHORT_LENGTH = 7;

    private Hashes() {
    }

    public static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java platform", e);
        }
    }

    public static String sha256Hex(byte[] data) {
        return HexFormat.of().formatHex(newSha256().digest(data));
    }

    public static String sha256Hex(String data) {
        return sha256Hex(data.getBytes(StandardCharsets.UTF_8));
    }

    public static boolean isHash(String value) {
        return value != null && SHA256_HEX.matcher(value).matches();
    }

    public static String requireHash(String value, String name) {
        Objects.requireNonNull(value, name);
        if (!isHash(value)) {
            throw new IllegalArgumentException(name + " is not a lowercase SHA-256 hex hash: " + value);
        }
        return value;
    }

    /** Git-style abbreviated hash. */
    public static String shortHash(String hash) {
        return hash.length() <= SHORT_LENGTH ? hash : hash.substring(0, SHORT_LENGTH);
    }
}
