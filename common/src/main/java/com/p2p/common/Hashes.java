package com.p2p.common;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class Hashes {
    private Hashes() {}

    public static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static byte[] sha256(byte[] data) {
        return sha256().digest(data);
    }

    public static String hex(byte[] b) {
        return HexFormat.of().formatHex(b);
    }

    public static byte[] unhex(String s) {
        return HexFormat.of().parseHex(s);
    }
}
