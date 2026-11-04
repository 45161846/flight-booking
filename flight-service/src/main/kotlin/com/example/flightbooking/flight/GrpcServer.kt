package com.example.flightbooking.flight

import io.grpc.*
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import io.grpc.protobuf.services.ProtoReflectionService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.SmartLifecycle
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class ApiKeyInterceptor(private val apiKey: String) : ServerInterceptor {
    init { require(apiKey.isNotBlank()) { "GRPC_API_KEY must not be blank" } }
    override fun <ReqT : Any?, RespT : Any?> interceptCall(
        call: ServerCall<ReqT, RespT>, headers: Metadata, next: ServerCallHandler<ReqT, RespT>
    ): ServerCall.Listener<ReqT> {
        val supplied = headers.get(Metadata.Key.of("x-api-key", Metadata.ASCII_STRING_MARSHALLER))
        if (supplied == null || !MessageDigest.isEqual(supplied.toByteArray(Charsets.UTF_8), apiKey.toByteArray(Charsets.UTF_8))) {
            call.close(Status.UNAUTHENTICATED.withDescription("Valid x-api-key metadata required"), Metadata())
            return object : ServerCall.Listener<ReqT>() {}
        }
        return next.startCall(call, headers)
    }
}

@Component
class GrpcServer(
    private val service: FlightGrpcService,
    @Value("\${flight.grpc-port}") private val port: Int,
    @Value("\${flight.api-key}") private val apiKey: String
) : SmartLifecycle {
    private var server: Server? = null
    private val log = LoggerFactory.getLogger(javaClass)
    override fun start() {
        server = NettyServerBuilder.forPort(port).intercept(ApiKeyInterceptor(apiKey))
            .addService(service).addService(ProtoReflectionService.newInstance()).build().start()
        log.info("Flight gRPC server listening on {}", server!!.port)
    }
    override fun stop() {
        server?.shutdown()?.let { if (!it.awaitTermination(5, TimeUnit.SECONDS)) it.shutdownNow() }
        server = null
    }
    override fun isRunning(): Boolean = server?.isShutdown == false
}
