package com.p2p.tracker;

import com.google.protobuf.ByteString;
import com.p2p.common.AuthInterceptor;
import com.p2p.common.Bitfields;
import com.p2p.common.Principal;
import com.p2p.common.RemoteKey;
import com.p2p.common.Tokens;
import com.p2p.proto.AnnounceRequest;
import com.p2p.proto.Empty;
import com.p2p.proto.GetPeersRequest;
import com.p2p.proto.GetPeersResponse;
import com.p2p.proto.HeartbeatAck;
import com.p2p.proto.PeerEndpoint;
import com.p2p.proto.PeerId;
import com.p2p.proto.PeerInfo;
import com.p2p.proto.PeerSession;
import com.p2p.proto.TrackerServiceGrpc;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import io.jsonwebtoken.JwtException;
import net.devh.boot.grpc.server.service.GrpcService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.springframework.data.mongodb.core.query.Criteria.where;

/** gRPC TrackerService: who is online and which chunks each peer has, per file. */
@GrpcService
class TrackerServiceImpl extends TrackerServiceGrpc.TrackerServiceImplBase {
    private static final Logger log = LoggerFactory.getLogger(TrackerServiceImpl.class);
    private static final int HEARTBEAT_SECONDS = 10;
    private static final Duration PEER_TTL = Duration.ofSeconds(30);

    private final PeerRepository peers;
    private final SwarmRepository swarm;
    private final MongoTemplate mongo;
    private final RemoteKey catalogKey;

    TrackerServiceImpl(PeerRepository peers, SwarmRepository swarm, MongoTemplate mongo,
                       @Qualifier("catalogKey") RemoteKey catalogKey) {
        this.peers = peers;
        this.swarm = swarm;
        this.mongo = mongo;
        this.catalogKey = catalogKey;
    }

    @Override
    public void registerPeer(PeerInfo req, StreamObserver<PeerSession> out) {
        Principal p = AuthInterceptor.require();
        if (req.getPeerId().isBlank() || req.getPort() <= 0) {
            throw Status.INVALID_ARGUMENT.withDescription("peer id and port are required").asRuntimeException();
        }
        peers.save(new PeerRecord(req.getPeerId(), req.getName(), p.id(), p.username(),
                req.getHost().isBlank() ? "localhost" : req.getHost(), req.getPort(), Instant.now()));
        swarm.deleteByPeerId(req.getPeerId()); // the peer re-announces everything it has
        log.info("peer {} online at {}:{} as {}", req.getName(), req.getHost(), req.getPort(), p.username());
        out.onNext(PeerSession.newBuilder().setHeartbeatIntervalSeconds(HEARTBEAT_SECONDS).build());
        out.onCompleted();
    }

    @Override
    public void heartbeat(PeerId req, StreamObserver<HeartbeatAck> out) {
        long matched = mongo.updateFirst(Query.query(where("_id").is(req.getPeerId())),
                Update.update("lastSeen", Instant.now()), PeerRecord.class).getMatchedCount();
        out.onNext(HeartbeatAck.newBuilder().setKnown(matched > 0).build());
        out.onCompleted();
    }

    @Override
    public void announce(AnnounceRequest req, StreamObserver<Empty> out) {
        Principal p = AuthInterceptor.require();
        checkTicket(req.getTicket(), req.getFileId());
        PeerRecord peer = peers.findById(req.getPeerId())
                .orElseThrow(() -> Status.FAILED_PRECONDITION.withDescription("unknown peer - register first").asRuntimeException());
        if (!p.id().equals(peer.principal())) {
            throw Status.PERMISSION_DENIED.withDescription("peer belongs to another user").asRuntimeException();
        }
        int have = Bitfields.fromBytes(req.getBitfield().toByteArray(), req.getChunkCount()).cardinality();
        String id = req.getFileId() + ":" + req.getPeerId();
        if (have == 0) {
            swarm.deleteById(id);
        } else {
            swarm.save(new SwarmEntry(id, req.getFileId(), req.getPeerId(), req.getBitfield().toByteArray(),
                    req.getChunkCount(), have, Instant.now()));
        }
        out.onNext(Empty.getDefaultInstance());
        out.onCompleted();
    }

    @Override
    public void getPeers(GetPeersRequest req, StreamObserver<GetPeersResponse> out) {
        AuthInterceptor.require();
        checkTicket(req.getTicket(), req.getFileId());
        Map<String, PeerRecord> alive = peers.findByLastSeenAfter(cutoff()).stream()
                .collect(Collectors.toMap(PeerRecord::id, Function.identity()));
        GetPeersResponse.Builder b = GetPeersResponse.newBuilder();
        for (SwarmEntry s : swarm.findByFileIdAndHaveCountGreaterThan(req.getFileId(), 0)) {
            PeerRecord peer = alive.get(s.peerId());
            if (peer == null || peer.id().equals(req.getPeerId())) {
                continue;
            }
            b.addPeers(PeerEndpoint.newBuilder()
                    .setPeerId(peer.id())
                    .setName(peer.name())
                    .setHost(peer.host())
                    .setPort(peer.port())
                    .setBitfield(ByteString.copyFrom(s.bitfield())));
        }
        out.onNext(b.build());
        out.onCompleted();
    }

    @Override
    public void leave(PeerId req, StreamObserver<Empty> out) {
        Principal p = AuthInterceptor.require();
        peers.findById(req.getPeerId()).filter(peer -> p.id().equals(peer.principal())).ifPresent(peer -> {
            peers.deleteById(peer.id());
            swarm.deleteByPeerId(peer.id());
            log.info("peer {} left", peer.name());
        });
        out.onNext(Empty.getDefaultInstance());
        out.onCompleted();
    }

    /** Swarm rows of peers whose heartbeats stopped (MongoDB's TTL monitor only runs once a minute). */
    @Scheduled(fixedDelay = 30_000, initialDelay = 30_000)
    void removeDeadSwarmEntries() {
        try {
            var aliveIds = peers.findByLastSeenAfter(cutoff()).stream().map(PeerRecord::id).toList();
            long n = swarm.deleteByPeerIdNotIn(aliveIds);
            if (n > 0) {
                log.info("removed {} swarm entries of offline peers", n);
            }
        } catch (RuntimeException e) {
            log.warn("swarm cleanup failed: {}", e.getMessage());
        }
    }

    private static Instant cutoff() {
        return Instant.now().minus(PEER_TTL);
    }

    private void checkTicket(String ticket, String fileId) {
        try {
            if (!Tokens.parseTicket(catalogKey.get(), ticket).fileId().equals(fileId)) {
                throw Status.PERMISSION_DENIED.withDescription("ticket is for another file").asRuntimeException();
            }
        } catch (JwtException | IllegalArgumentException e) {
            throw Status.PERMISSION_DENIED.withDescription("invalid or expired download ticket").asRuntimeException();
        }
    }
}
