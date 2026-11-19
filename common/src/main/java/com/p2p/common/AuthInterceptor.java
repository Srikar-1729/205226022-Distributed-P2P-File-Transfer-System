package com.p2p.common;

import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.jsonwebtoken.JwtException;

import java.security.PublicKey;
import java.util.function.Supplier;

/**
 * Server-side JWT check. A valid {@code authorization: Bearer} header puts the caller's {@link Principal}
 * into the gRPC context; an invalid one is rejected with UNAUTHENTICATED; no header means an anonymous call
 * (methods that need a caller use {@link #require()}).
 */
public final class AuthInterceptor implements ServerInterceptor {
    private static final Context.Key<Principal> PRINCIPAL = Context.key("principal");
    private final Supplier<PublicKey> authKey;

    public AuthInterceptor(Supplier<PublicKey> authKey) {
        this.authKey = authKey;
    }

    @Override
    public <Q, R> ServerCall.Listener<Q> interceptCall(ServerCall<Q, R> call, Metadata headers, ServerCallHandler<Q, R> next) {
        String h = headers.get(GrpcSupport.AUTHORIZATION);
        if (h == null || !h.startsWith("Bearer ")) {
            return next.startCall(call, headers);
        }
        try {
            Principal p = Tokens.parseUserToken(authKey.get(), h.substring("Bearer ".length()));
            return Contexts.interceptCall(Context.current().withValue(PRINCIPAL, p), call, headers, next);
        } catch (JwtException | IllegalArgumentException e) {
            call.close(Status.UNAUTHENTICATED.withDescription("invalid or expired token"), new Metadata());
        } catch (StatusRuntimeException e) {
            call.close(e.getStatus(), new Metadata());
        }
        return new ServerCall.Listener<>() {};
    }

    /** The caller, or null for an anonymous call. */
    public static Principal current() {
        return PRINCIPAL.get();
    }

    public static Principal require() {
        Principal p = current();
        if (p == null) {
            throw Status.UNAUTHENTICATED.withDescription("login required").asRuntimeException();
        }
        return p;
    }
}
