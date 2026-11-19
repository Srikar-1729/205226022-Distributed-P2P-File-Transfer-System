package com.p2p.catalog;

import com.p2p.common.AuthInterceptor;
import com.p2p.common.GrpcServerConfig;
import com.p2p.common.Keys;
import com.p2p.common.RemoteKey;
import com.p2p.proto.AuthServiceGrpc;
import com.p2p.proto.Empty;
import net.devh.boot.grpc.client.inject.GrpcClient;
import net.devh.boot.grpc.server.interceptor.GrpcGlobalServerInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.nio.file.Path;
import java.security.KeyPair;
import java.util.concurrent.TimeUnit;

/** Catalog microservice: file manifests, access rules, Download Tickets. gRPC on :9092, database {@code catalog_db}. */
@SpringBootApplication
@Import(GrpcServerConfig.class)
public class CatalogApplication {

    @GrpcClient("auth")
    private AuthServiceGrpc.AuthServiceBlockingStub auth;

    public static void main(String[] args) {
        SpringApplication.run(CatalogApplication.class, args);
    }

    /** RSA key pair that signs Download Tickets. */
    @Bean
    KeyPair signingKeys(@Value("${p2p.keys-dir}") Path keysDir) {
        return Keys.loadOrCreate(keysDir, "catalog");
    }

    /** Auth service's public key, fetched once, to verify callers' JWTs locally. */
    @Bean
    RemoteKey authKey() {
        return new RemoteKey("auth-service", () ->
                auth.withDeadlineAfter(5, TimeUnit.SECONDS).getPublicKey(Empty.getDefaultInstance()));
    }

    @GrpcGlobalServerInterceptor
    AuthInterceptor authInterceptor(RemoteKey authKey) {
        return new AuthInterceptor(authKey);
    }
}
