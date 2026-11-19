package com.p2p.common;

import com.p2p.proto.PublicKey;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;

import java.util.concurrent.Callable;
import java.util.function.Supplier;

/**
 * Another service's public key, fetched once over gRPC ({@code GetPublicKey}) and cached.
 * Lets every service verify JWTs locally instead of calling the issuer on every request.
 */
public final class RemoteKey implements Supplier<java.security.PublicKey> {
    private final String what;
    private final Callable<PublicKey> fetch;
    private volatile java.security.PublicKey cached;

    public RemoteKey(String what, Callable<PublicKey> fetch) {
        this.what = what;
        this.fetch = fetch;
    }

    @Override
    public java.security.PublicKey get() {
        java.security.PublicKey k = cached;
        if (k != null) {
            return k;
        }
        synchronized (this) {
            if (cached == null) {
                try {
                    cached = Keys.decodePublic(fetch.call().getDer().toByteArray());
                } catch (StatusRuntimeException e) {
                    throw Status.UNAVAILABLE.withDescription("cannot reach " + what + " to fetch its public key")
                            .withCause(e).asRuntimeException();
                } catch (Exception e) {
                    throw Status.INTERNAL.withDescription("cannot load " + what + " public key").withCause(e).asRuntimeException();
                }
            }
            return cached;
        }
    }
}
