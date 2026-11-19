package com.p2p.peer;

import com.google.protobuf.ByteString;
import com.p2p.common.Bitfields;
import com.p2p.common.GrpcSupport;
import com.p2p.peer.LocalFile.Kind;
import com.p2p.peer.LocalFile.Status;
import com.p2p.proto.AnnounceRequest;
import com.p2p.proto.AuthResponse;
import com.p2p.proto.AuthServiceGrpc;
import com.p2p.proto.CatalogServiceGrpc;
import com.p2p.proto.Empty;
import com.p2p.proto.FileInfo;
import com.p2p.proto.LoginRequest;
import com.p2p.proto.Manifest;
import com.p2p.proto.PeerId;
import com.p2p.proto.PeerInfo;
import com.p2p.proto.PeerTransferServiceGrpc;
import com.p2p.proto.RegisterFileRequest;
import com.p2p.proto.RegisterRequest;
import com.p2p.proto.TrackerServiceGrpc;
import com.p2p.proto.Visibility;
import io.grpc.ManagedChannel;
import io.grpc.Status.Code;
import io.grpc.StatusRuntimeException;
import jakarta.annotation.PreDestroy;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * One peer's core: session, gRPC clients (Auth, Catalog, Tracker via {@code @GrpcClient}; other peers via
 * channels built at runtime), tracker heartbeats and announcements, sharing and downloads.
 */
@Service
public class PeerNode {
    private static final Logger log = LoggerFactory.getLogger(PeerNode.class);

    final String name;
    final String host;
    final int grpcPort;
    final int uiPort;
    final Path dataDir;
    final String peerId;
    final int maxPeers;
    final int perPeerInflight;
    final boolean tls = true;
    final RateLimiter uploadLimiter;
    final FileStore store;
    final Session session;
    final TicketCache tickets;
    final DownloadManager downloads;
    final ExecutorService transferPool = Executors.newFixedThreadPool(16, r -> {
        Thread t = new Thread(r, "transfer");
        t.setDaemon(true);
        return t;
    });

    private final PeerProperties props;
    private final Map<String, ManagedChannel> peerChannels = new ConcurrentHashMap<>();
    private volatile boolean registered;
    volatile boolean trackerOnline;
    volatile String trackerError;

    @GrpcClient("auth")
    private AuthServiceGrpc.AuthServiceBlockingStub authStub;
    @GrpcClient("catalog")
    private CatalogServiceGrpc.CatalogServiceBlockingStub catalogStub;
    @GrpcClient("tracker")
    private TrackerServiceGrpc.TrackerServiceBlockingStub trackerStub;

    PeerNode(PeerProperties props, FileStore store, Session session, RateLimiter uploadLimiter,
             @Value("${grpc.server.port}") int grpcPort, @Value("${server.port}") int uiPort) throws IOException {
        this.props = props;
        this.name = props.name();
        this.host = props.host();
        this.dataDir = props.dataDir();
        this.maxPeers = props.maxPeers();
        this.perPeerInflight = props.perPeer();
        this.grpcPort = grpcPort;
        this.uiPort = uiPort;
        this.store = store;
        this.session = session;
        this.uploadLimiter = uploadLimiter;

        Path idFile = dataDir.resolve("peer-id");
        if (!Files.exists(idFile)) {
            Files.writeString(idFile, UUID.randomUUID().toString());
        }
        peerId = Files.readString(idFile).trim();
        tickets = new TicketCache(this::catalog);
        downloads = new DownloadManager(this);
    }

    // ------------------------------------------------------------------ gRPC stubs

    AuthServiceGrpc.AuthServiceBlockingStub auth() {
        return authStub.withDeadlineAfter(10, TimeUnit.SECONDS);
    }

    CatalogServiceGrpc.CatalogServiceBlockingStub catalog() {
        return catalogStub.withInterceptors(GrpcSupport.bearer(session::token)).withDeadlineAfter(10, TimeUnit.SECONDS);
    }

    TrackerServiceGrpc.TrackerServiceBlockingStub tracker() {
        return trackerStub.withInterceptors(GrpcSupport.bearer(session::token)).withDeadlineAfter(10, TimeUnit.SECONDS);
    }

    /** Peers are found at runtime, so their channels can't come from configuration. */
    PeerTransferServiceGrpc.PeerTransferServiceBlockingStub peerStub(String peerHost, int port) {
        return PeerTransferServiceGrpc.newBlockingStub(peerChannels.computeIfAbsent(peerHost + ":" + port,
                t -> GrpcSupport.tlsChannel(t, props.caCert().toFile())));
    }

    // ------------------------------------------------------------------ lifecycle

    @EventListener(ApplicationReadyEvent.class)
    void start() {
        store.loadAll();
        session.load();
        if (session.loggedIn()) {
            downloads.resumeAll();
        }
        log.info("{} ready - open http://localhost:{}", name, uiPort);
    }

    @PreDestroy
    void close() {
        downloads.stopAll();
        if (session.loggedIn()) {
            leave();
        }
        peerChannels.values().forEach(ManagedChannel::shutdownNow);
    }

    // ------------------------------------------------------------------ session

    AuthResponse register(String username, String password) {
        return loggedIn(auth().register(RegisterRequest.newBuilder().setUsername(username).setPassword(password).build()));
    }

    AuthResponse login(String username, String password) {
        return loggedIn(auth().login(LoginRequest.newBuilder().setUsername(username).setPassword(password).build()));
    }

    AuthResponse guest() {
        return loggedIn(auth().guestLogin(Empty.getDefaultInstance()));
    }

    private AuthResponse loggedIn(AuthResponse r) {
        if (session.loggedIn()) {
            logout();
        }
        session.save(r);
        tickets.clear();
        registered = false;
        log.info("logged in as {} ({})", r.getUsername(), r.getRole());
        transferPool.execute(this::trackerTick);
        downloads.resumeAll();
        return r;
    }

    void logout() {
        downloads.stopAll();
        leave();
        session.clear();
        tickets.clear();
        registered = false;
        trackerOnline = false;
    }

    // ------------------------------------------------------------------ tracker link

    /** Every 10 s: register (first time / after a tracker restart) or send a heartbeat. */
    @Scheduled(fixedDelay = 10_000, initialDelay = 1_000)
    synchronized void trackerTick() {
        if (!session.loggedIn()) {
            return;
        }
        try {
            if (registered && tracker().heartbeat(PeerId.newBuilder().setPeerId(peerId).build()).getKnown()) {
                trackerOnline = true;
                return;
            }
            tracker().registerPeer(PeerInfo.newBuilder().setPeerId(peerId).setName(name).setHost(host).setPort(grpcPort).build());
            registered = true;
            trackerOnline = true;
            trackerError = null;
            store.all().forEach(f -> f.dirty = true); // re-announce everything we have
            log.info("registered with tracker as {}", name);
            announceChanges();
        } catch (StatusRuntimeException e) {
            registered = false;
            trackerOnline = false;
            trackerError = e.getStatus().getCode() + ": " + e.getStatus().getDescription();
            log.warn("tracker: {}", trackerError);
        }
    }

    /** Every 2 s: tell the tracker about bitfields that changed (batched announcements). */
    @Scheduled(fixedDelay = 2_000, initialDelay = 2_000)
    synchronized void announceChanges() {
        if (!registered || !session.loggedIn()) {
            return;
        }
        for (LocalFile f : store.all()) {
            if (!f.dirty || f.haveCount() == 0) {
                continue;
            }
            try {
                tracker().announce(AnnounceRequest.newBuilder().setPeerId(peerId).setFileId(f.id())
                        .setBitfield(ByteString.copyFrom(f.bitfield()))
                        .setChunkCount(f.chunkCount()).setTicket(tickets.ticket(f.id())).build());
                f.dirty = false;
            } catch (StatusRuntimeException e) {
                Code c = e.getStatus().getCode();
                if (c == Code.NOT_FOUND || c == Code.PERMISSION_DENIED) {
                    f.dirty = false; // deleted from the catalog or access revoked for this user: stop announcing
                    f.error = "not announced: " + e.getStatus().getDescription();
                } else if (c == Code.FAILED_PRECONDITION) {
                    registered = false;
                    return;
                } else {
                    log.warn("announce of {} failed: {}", f.manifest.getName(), e.getStatus());
                    return;
                }
            }
        }
    }

    private void leave() {
        try {
            tracker().withDeadlineAfter(2, TimeUnit.SECONDS).leave(PeerId.newBuilder().setPeerId(peerId).build());
        } catch (StatusRuntimeException e) {
            log.debug("leave failed: {}", e.getStatus());
        }
    }

    // ------------------------------------------------------------------ sharing

    /** Share Manager: chunk + hash the file, register it with the Catalog, then seed it. */
    FileInfo share(Path file, String displayName, Visibility visibility, List<String> allowedUsers) throws IOException {
        long t0 = System.currentTimeMillis();
        Manifest m = FileStore.buildManifest(file, displayName);
        log.info("hashed '{}' ({} chunks) in {} ms", displayName, m.getChunkCount(), System.currentTimeMillis() - t0);
        FileInfo info = catalog().registerFile(RegisterFileRequest.newBuilder()
                .setManifest(m).setVisibility(visibility).addAllAllowedUsers(allowedUsers).build());
        Manifest withId = m.toBuilder().setFileId(info.getFileId()).build();
        store.add(new LocalFile(withId, Kind.SHARED, Bitfields.full(m.getChunkCount()), file, Status.SEEDING));
        announceChanges();
        return info;
    }

    /** Owner deleted the file from the catalog: stop sharing it (the bytes stay on disk). */
    void forget(String fileId) {
        downloads.remove(fileId);
    }
}
