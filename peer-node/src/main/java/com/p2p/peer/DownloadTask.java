package com.p2p.peer;

import com.p2p.common.Bitfields;
import com.p2p.common.Hashes;
import com.p2p.peer.LocalFile.Status;
import com.p2p.proto.Bitfield;
import com.p2p.proto.BitfieldRequest;
import com.p2p.proto.ChunkPiece;
import com.p2p.proto.ChunkRequest;
import com.p2p.proto.GetPeersRequest;
import com.p2p.proto.GetPeersResponse;
import com.p2p.proto.PeerEndpoint;
import io.grpc.StatusRuntimeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Downloads one file from many peers in parallel (docs/diagrams/04 + 05).
 * One coordinator thread owns all scheduling state; chunk fetches run on the node's transfer pool
 * and report back through {@link #results}.
 */
final class DownloadTask implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(DownloadTask.class);
    private static final int MAX_STRIKES = 3;
    private static final long BAN_MS = 60_000;
    private static final long REFRESH_MS = 20_000;
    private static final long REFRESH_WHEN_STUCK_MS = 5_000;
    private static final long BITFIELD_REFRESH_MS = 3_000;

    private final PeerNode node;
    private final LocalFile f;
    private final Map<String, Remote> peers = new HashMap<>();
    private final Map<Integer, Set<String>> inflight = new HashMap<>();
    private final BlockingQueue<Result> results = new LinkedBlockingQueue<>();
    private final Random rnd = new Random();
    private volatile boolean stop;
    private volatile Status stopStatus = Status.PAUSED;
    private long lastRefresh;
    private long lastBitfieldRefresh;
    private boolean everFoundPeers;
    private long speedAt = System.nanoTime();
    private long speedBytes;

    private static final class Remote {
        final String id;
        final String name;
        final String host;
        final int port;
        BitSet have = new BitSet();
        int inflight;
        int strikes;
        long bannedUntil;
        boolean gone;

        Remote(PeerEndpoint ep) {
            id = ep.getPeerId();
            name = ep.getName();
            host = ep.getHost();
            port = ep.getPort();
        }

        boolean usable(long now) {
            return !gone && now >= bannedUntil;
        }
    }

    private record Result(int index, Remote peer, byte[] data, Throwable error) {}

    DownloadTask(PeerNode node, LocalFile f) {
        this.node = node;
        this.f = f;
    }

    /** Stops after the current step; {@code status} is what the file shows afterwards (PAUSED or QUEUED). */
    void requestStop(Status status) {
        stopStatus = status;
        stop = true;
    }

    @Override
    public void run() {
        try {
            loop();
        } catch (StatusRuntimeException e) {
            fail(e.getStatus().getCode() + ": " + e.getStatus().getDescription());
        } catch (Exception e) {
            log.error("download of {} failed", f.manifest.getName(), e);
            fail(e.toString());
        } finally {
            f.speed = 0;
            f.activePeers = 0;
            if (stop) {
                f.status = stopStatus;
                node.store.saveState(f);
            }
        }
    }

    private void loop() throws Exception {
        f.error = null;
        speedBytes = f.downloaded.get();
        log.info("downloading '{}' ({} chunks, {} already here)", f.manifest.getName(), f.chunkCount(), f.haveCount());
        while (!stop) {
            if (f.complete()) {
                if (finish()) {
                    return;
                }
                continue;
            }
            long now = System.currentTimeMillis();
            boolean stuck = inflight.isEmpty();
            if (now - lastRefresh > (stuck ? REFRESH_WHEN_STUCK_MS : REFRESH_MS)) {
                refreshPeers();
            } else if (now - lastBitfieldRefresh > BITFIELD_REFRESH_MS) {
                refreshBitfields();
            }
            launchAssignments();
            if (!stop) {
                f.status = !inflight.isEmpty() ? Status.DOWNLOADING : everFoundPeers ? Status.STALLED : Status.FINDING_PEERS;
            }
            Result r = results.poll(250, TimeUnit.MILLISECONDS);
            while (r != null && !stop) {
                handle(r);
                r = results.poll();
            }
            updateSpeed();
        }
    }

    // ------------------------------------------------------------------ peers

    private void refreshPeers() {
        lastRefresh = System.currentTimeMillis();
        GetPeersResponse resp;
        try {
            resp = node.tracker().getPeers(GetPeersRequest.newBuilder()
                    .setFileId(f.id()).setTicket(node.tickets.ticket(f.id())).setPeerId(node.peerId).build());
        } catch (StatusRuntimeException e) {
            if (e.getStatus().getCode() == io.grpc.Status.Code.UNAVAILABLE || e.getStatus().getCode() == io.grpc.Status.Code.DEADLINE_EXCEEDED) {
                log.warn("tracker unavailable - continuing with {} known peers", peers.size());
                return;
            }
            throw e;
        }
        Set<String> seen = new HashSet<>();
        for (PeerEndpoint ep : resp.getPeersList()) {
            Remote r = peers.computeIfAbsent(ep.getPeerId(), id -> new Remote(ep));
            r.have = Bitfields.fromBytes(ep.getBitfield().toByteArray(), f.chunkCount());
            r.gone = false;
            seen.add(r.id);
        }
        peers.values().removeIf(r -> !seen.contains(r.id) && r.inflight == 0);
        peers.values().forEach(r -> r.gone = !seen.contains(r.id));
        if (!seen.isEmpty()) {
            everFoundPeers = true;
        }
    }

    /**
     * Between tracker queries, ask the known peers directly what they have now ({@code PeerTransfer.GetBitfield}),
     * so peers that are downloading the same file start trading freshly verified chunks within seconds.
     */
    private void refreshBitfields() {
        lastBitfieldRefresh = System.currentTimeMillis();
        String ticket = node.tickets.ticket(f.id());
        for (Remote r : peers.values()) {
            if (!r.usable(lastBitfieldRefresh) || r.have.cardinality() == f.chunkCount()) {
                continue; // seeders never change
            }
            try {
                Bitfield b = node.peerStub(r.host, r.port).withDeadlineAfter(2, TimeUnit.SECONDS)
                        .getBitfield(BitfieldRequest.newBuilder().setFileId(f.id()).setTicket(ticket).build());
                r.have = Bitfields.fromBytes(b.getBitfield().toByteArray(), f.chunkCount());
            } catch (StatusRuntimeException e) {
                log.debug("bitfield of {} unavailable: {}", r.name, e.getStatus());
            }
        }
    }

    private void launchAssignments() {
        long now = System.currentTimeMillis();
        List<ChunkScheduler.PeerState> states = new ArrayList<>();
        for (Remote r : peers.values()) {
            if (r.usable(now)) {
                states.add(new ChunkScheduler.PeerState(r.id, r.have, r.inflight));
            }
        }
        List<ChunkScheduler.Assignment> plan = ChunkScheduler.plan(f.chunkCount(), f.haveCopy(), inflight, states,
                node.maxPeers, node.perPeerInflight, rnd);
        if (!plan.isEmpty()) {
            String ticket = node.tickets.ticket(f.id());
            for (ChunkScheduler.Assignment a : plan) {
                launch(a.chunk(), peers.get(a.peerId()), ticket);
            }
        }
        f.activePeers = (int) peers.values().stream().filter(r -> r.inflight > 0).count();
    }

    private void launch(int index, Remote peer, String ticket) {
        inflight.computeIfAbsent(index, k -> new HashSet<>()).add(peer.id);
        peer.inflight++;
        node.transferPool.submit(() -> {
            try {
                results.add(new Result(index, peer, fetch(peer, index, ticket), null));
            } catch (Throwable t) {
                results.add(new Result(index, peer, null, t));
            }
        });
    }

    /** {@code PeerTransfer.DownloadChunk}: reassemble the 64 KiB pieces of one chunk. */
    private byte[] fetch(Remote peer, int index, String ticket) throws IOException {
        int len = f.chunkLength(index);
        byte[] buf = new byte[len];
        int pos = 0;
        Iterator<ChunkPiece> it = node.peerStub(peer.host, peer.port).withDeadlineAfter(60, TimeUnit.SECONDS)
                .downloadChunk(ChunkRequest.newBuilder().setFileId(f.id()).setIndex(index).setTicket(ticket).build());
        while (it.hasNext()) {
            ChunkPiece p = it.next();
            int n = p.getData().size();
            if (p.getOffset() != pos || pos + n > len) {
                throw new IOException("out-of-order or oversized piece");
            }
            p.getData().copyTo(buf, pos);
            pos += n;
        }
        if (pos != len) {
            throw new IOException("chunk ended early (" + pos + "/" + len + " bytes)");
        }
        return buf;
    }

    private void handle(Result r) throws IOException {
        Set<String> s = inflight.get(r.index);
        if (s != null) {
            s.remove(r.peer.id);
            if (s.isEmpty()) {
                inflight.remove(r.index);
            }
        }
        r.peer.inflight--;
        if (f.has(r.index)) {
            return; // endgame duplicate that lost the race
        }
        if (r.error != null) {
            if (r.error instanceof StatusRuntimeException sre && sre.getStatus().getCode() == io.grpc.Status.Code.NOT_FOUND) {
                r.peer.have.clear(r.index); // peer no longer has it: not its fault
            } else {
                strike(r.peer, "chunk " + r.index + " failed: " + describe(r.error));
            }
            return;
        }
        if (!Arrays.equals(Hashes.sha256(r.data), f.manifest.getChunkHashes(r.index).toByteArray())) {
            strike(r.peer, "SHA-256 mismatch on chunk " + r.index + " - discarded");
            return;
        }
        node.store.writeChunk(f, r.index, r.data);
        f.markHave(r.index, r.peer.name);
        f.downloaded.addAndGet(r.data.length);
        node.store.saveState(f);
    }

    private void strike(Remote peer, String why) {
        peer.strikes++;
        log.warn("{} from {} (strike {}/{})", why, peer.name, peer.strikes, MAX_STRIKES);
        if (peer.strikes >= MAX_STRIKES) {
            peer.strikes = 0;
            peer.bannedUntil = System.currentTimeMillis() + BAN_MS;
            log.warn("ignoring {} for {} s", peer.name, BAN_MS / 1000);
        }
    }

    // ------------------------------------------------------------------ completion

    private boolean finish() throws IOException {
        f.status = Status.VERIFYING;
        f.speed = 0;
        f.activePeers = 0;
        if (Arrays.equals(FileStore.hashFile(f.dataPath), f.manifest.getFileHash().toByteArray())) {
            node.store.completeDownload(f);
            f.dirty = true;
            log.info("'{}' complete and verified -> {}", f.manifest.getName(), f.dataPath);
            return true;
        }
        int bad = 0;
        for (int i = 0; i < f.chunkCount(); i++) {
            if (!node.store.verifyChunk(f, i)) {
                f.clear(i);
                bad++;
            }
        }
        node.store.saveState(f);
        if (bad == 0) {
            throw new IOException("file hash does not match the manifest (corrupt manifest?)");
        }
        log.warn("whole-file hash mismatch: re-fetching {} bad chunks", bad);
        f.status = Status.DOWNLOADING;
        return false;
    }

    private void fail(String message) {
        if (stop) {
            return;
        }
        log.warn("download of '{}' failed: {}", f.manifest.getName(), message);
        f.status = Status.FAILED;
        f.error = message;
        node.store.saveState(f);
    }

    private void updateSpeed() {
        long now = System.nanoTime();
        if (now - speedAt >= 1_000_000_000L) {
            long bytes = f.downloaded.get();
            f.speed = (bytes - speedBytes) * 1_000_000_000L / (now - speedAt);
            speedBytes = bytes;
            speedAt = now;
        }
    }

    private static String describe(Throwable t) {
        if (t instanceof StatusRuntimeException sre) {
            return sre.getStatus().getCode() + (sre.getStatus().getDescription() != null ? " " + sre.getStatus().getDescription() : "");
        }
        return t.toString();
    }
}
