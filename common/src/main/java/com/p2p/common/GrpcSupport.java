package com.p2p.common;

import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.Grpc;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.TlsChannelCredentials;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.function.Supplier;

/**
 * gRPC helpers that Spring's {@code @GrpcClient} can't cover: peers connect to each other at addresses
 * learned at runtime from the tracker, so those channels are built here (TLS, trusting the local CA).
 */
public final class GrpcSupport {
    public static final int MAX_MESSAGE = 8 * 1024 * 1024;
    public static final Metadata.Key<String> AUTHORIZATION =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

    private GrpcSupport() {}

    public static ManagedChannel tlsChannel(String target, File caCertificate) {
        try {
            return Grpc.newChannelBuilder(target, TlsChannelCredentials.newBuilder().trustManager(caCertificate).build())
                    .maxInboundMessageSize(MAX_MESSAGE).build();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read CA certificate " + caCertificate, e);
        }
    }

    /** Adds {@code authorization: Bearer <token>} to every outgoing call when a token is available. */
    public static ClientInterceptor bearer(Supplier<String> token) {
        return new ClientInterceptor() {
            @Override
            public <Q, R> io.grpc.ClientCall<Q, R> interceptCall(io.grpc.MethodDescriptor<Q, R> method,
                                                                 io.grpc.CallOptions options, io.grpc.Channel next) {
                return new ForwardingClientCall.SimpleForwardingClientCall<>(next.newCall(method, options)) {
                    @Override
                    public void start(Listener<R> listener, Metadata headers) {
                        String t = token.get();
                        if (t != null) {
                            headers.put(AUTHORIZATION, "Bearer " + t);
                        }
                        super.start(listener, headers);
                    }
                };
            }
        };
    }
}
