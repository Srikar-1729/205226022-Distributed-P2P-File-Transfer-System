package com.p2p.tracker;

import com.p2p.common.AuthInterceptor;
import com.p2p.common.GrpcServerConfig;
import com.p2p.common.RemoteKey;
import com.p2p.proto.AuthServiceGrpc;
import com.p2p.proto.CatalogServiceGrpc;
import com.p2p.proto.Empty;
import net.devh.boot.grpc.client.inject.GrpcClient;
import net.devh.boot.grpc.server.interceptor.GrpcGlobalServerInterceptor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.concurrent.TimeUnit;

/** Tracker microservice: live peers + swarm bitfields. gRPC on :9093, database {@code tracker_db}. */
@SpringBootApplication
@EnableScheduling
@Import(GrpcServerConfig.class)
public class TrackerApplication {

    @GrpcClient("auth")
    private AuthServiceGrpc.AuthServiceBlockingStub auth;

    @GrpcClient("catalog")
    private CatalogServiceGrpc.CatalogServiceBlockingStub catalog;

    public static void main(String[] args) {
        SpringApplication.run(TrackerApplication.class, args);
    }

    /** Verifies callers' JWTs. */
    @Bean
    RemoteKey authKey() {
        return new RemoteKey("auth-service", () ->
                auth.withDeadlineAfter(5, TimeUnit.SECONDS).getPublicKey(Empty.getDefaultInstance()));
    }

    /** Verifies Download Tickets. */
    @Bean
    RemoteKey catalogKey() {
        return new RemoteKey("catalog-service", () ->
                catalog.withDeadlineAfter(5, TimeUnit.SECONDS).getPublicKey(Empty.getDefaultInstance()));
    }

    @GrpcGlobalServerInterceptor
    AuthInterceptor authInterceptor(RemoteKey authKey) {
        return new AuthInterceptor(authKey);
    }
}
