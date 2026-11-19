package com.p2p.peer;

import com.p2p.common.GrpcServerConfig;
import com.p2p.common.RemoteKey;
import com.p2p.proto.CatalogServiceGrpc;
import com.p2p.proto.Empty;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * One peer: {@code java -jar peer-node.jar --peer.index=3 [--peer.upload-limit=2MB] [--peer.debug-corrupt=true]}.
 * Index N gives name peer-N, web UI port 8080+N, gRPC port 50050+N and data folder peers/peer-N
 * (each can still be overridden on the command line).
 */
@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(PeerProperties.class)
@Import(GrpcServerConfig.class)
public class PeerApplication {

    @GrpcClient("catalog")
    private CatalogServiceGrpc.CatalogServiceBlockingStub catalog;

    public static void main(String[] args) {
        int n = 1;
        for (String a : args) {
            if (a.startsWith("--peer.index=") || a.startsWith("--index=")) {
                n = Integer.parseInt(a.substring(a.indexOf('=') + 1));
            }
        }
        Map<String, Object> defaults = new HashMap<>();
        defaults.put("peer.index", n);
        defaults.put("peer.name", "peer-" + n);
        defaults.put("peer.data-dir", "peers/peer-" + n);
        defaults.put("server.port", 8080 + n);
        defaults.put("grpc.server.port", 50050 + n);
        SpringApplication app = new SpringApplication(PeerApplication.class);
        app.setDefaultProperties(defaults);
        app.run(args);
    }

    /** Catalog's public key: verifies the Download Tickets other peers present. */
    @Bean
    RemoteKey catalogKey() {
        return new RemoteKey("catalog-service", () ->
                catalog.withDeadlineAfter(5, TimeUnit.SECONDS).getPublicKey(Empty.getDefaultInstance()));
    }

    @Bean
    RateLimiter uploadLimiter(PeerProperties props) {
        return new RateLimiter(props.uploadLimit().toBytes());
    }
}
