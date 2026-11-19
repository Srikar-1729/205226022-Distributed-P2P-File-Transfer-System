package com.p2p.auth;

import com.p2p.common.GrpcServerConfig;
import com.p2p.common.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.nio.file.Path;
import java.security.KeyPair;

/** Auth microservice: accounts, guest sessions, JWTs. gRPC on :9091, database {@code auth_db}. */
@SpringBootApplication
@Import(GrpcServerConfig.class)
public class AuthApplication {

    public static void main(String[] args) {
        SpringApplication.run(AuthApplication.class, args);
    }

    /** RSA key pair that signs user tokens; kept on disk so tokens stay valid across restarts. */
    @Bean
    KeyPair signingKeys(@Value("${p2p.keys-dir}") Path keysDir) {
        return Keys.loadOrCreate(keysDir, "auth");
    }
}
