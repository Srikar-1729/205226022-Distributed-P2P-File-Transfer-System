package com.p2p.common;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

/** RSA signing keys. Each issuing service keeps its key pair on disk so tokens survive restarts. */
public final class Keys {
    private Keys() {}

    public static KeyPair loadOrCreate(Path dir, String name) {
        Path priv = dir.resolve(name + ".key");
        Path pub = dir.resolve(name + ".pub");
        try {
            if (Files.exists(priv) && Files.exists(pub)) {
                KeyFactory kf = KeyFactory.getInstance("RSA");
                PrivateKey pk = kf.generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(priv)));
                return new KeyPair(decodePublic(Files.readAllBytes(pub)), pk);
            }
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(2048);
            KeyPair kp = g.generateKeyPair();
            Files.createDirectories(dir);
            Files.write(priv, kp.getPrivate().getEncoded());
            Files.write(pub, kp.getPublic().getEncoded());
            return kp;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static PublicKey decodePublic(byte[] der) {
        try {
            return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("bad public key", e);
        }
    }
}
