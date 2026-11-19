package com.p2p.common;

import io.grpc.ServerInterceptor;
import io.grpc.util.TransmitStatusRuntimeExceptionInterceptor;
import net.devh.boot.grpc.server.interceptor.GrpcGlobalServerInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

/**
 * Shared gRPC server setup, imported by every Spring Boot app ({@code @Import(GrpcServerConfig.class)}).
 * Services throw {@code StatusRuntimeException} (e.g. PERMISSION_DENIED); this interceptor makes sure the
 * client receives that status and message instead of a generic UNKNOWN.
 */
@Configuration(proxyBeanMethods = false)
public class GrpcServerConfig {

    @GrpcGlobalServerInterceptor
    @Order(Ordered.LOWEST_PRECEDENCE)
    ServerInterceptor transmitStatusExceptions() {
        return TransmitStatusRuntimeExceptionInterceptor.instance();
    }
}
