package com.p2p.peer;

import com.google.protobuf.ByteString;
import com.p2p.common.RemoteKey;
import com.p2p.common.Tokens;
import com.p2p.proto.Bitfield;
import com.p2p.proto.BitfieldRequest;
import com.p2p.proto.ChunkPiece;
import com.p2p.proto.ChunkRequest;
import com.p2p.proto.PeerTransferServiceGrpc;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import io.jsonwebtoken.JwtException;
import net.devh.boot.grpc.server.service.GrpcService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/** Serves verified chunks to other peers - but only to callers holding a valid Download Ticket for that file. */
@GrpcService
class PeerTransferServiceImpl extends PeerTransferServiceGrpc.PeerTransferServiceImplBase {
    private static final Logger log = LoggerFactory.getLogger(PeerTransferServiceImpl.class);
    static final int PIECE = 64 * 1024;

    private final FileStore store;
    private final RemoteKey catalogKey;
    private final RateLimiter limiter;
    private final boolean corrupt;

    PeerTransferServiceImpl(FileStore store, RemoteKey catalogKey, RateLimiter limiter, PeerProperties props) {
        this.store = store;
        this.catalogKey = catalogKey;
        this.limiter = limiter;
        this.corrupt = props.debugCorrupt();
    }

    @Override
    public void getBitfield(BitfieldRequest req, StreamObserver<Bitfield> out) {
        LocalFile f = authorized(req.getTicket(), req.getFileId());
        out.onNext(Bitfield.newBuilder().setBitfield(ByteString.copyFrom(f.bitfield())).setChunkCount(f.chunkCount()).build());
        out.onCompleted();
    }

    @Override
    public void downloadChunk(ChunkRequest req, StreamObserver<ChunkPiece> out) {
        LocalFile f = authorized(req.getTicket(), req.getFileId());
        int idx = req.getIndex();
        if (idx < 0 || idx >= f.chunkCount() || !f.has(idx)) {
            throw Status.NOT_FOUND.withDescription("chunk " + idx + " not available here").asRuntimeException();
        }
        byte[] data;
        try {
            data = store.readChunk(f, idx);
        } catch (IOException e) {
            throw Status.INTERNAL.withDescription("cannot read chunk: " + e.getMessage()).asRuntimeException();
        }
        if (corrupt) { // demo switch: --peer.debug-corrupt=true sends bad data so receivers' hash check kicks in
            data = data.clone();
            data[0] ^= 0x5A;
            log.warn("sending CORRUPTED chunk {} of {} (debug)", idx, f.manifest.getName());
        }
        try {
            for (int off = 0; off < data.length; off += PIECE) {
                int n = Math.min(PIECE, data.length - off);
                limiter.acquire(n);
                out.onNext(ChunkPiece.newBuilder().setIndex(idx).setOffset(off)
                        .setData(ByteString.copyFrom(data, off, n)).setLast(off + n == data.length).build());
                f.uploaded.addAndGet(n);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw Status.CANCELLED.asRuntimeException();
        }
        out.onCompleted();
    }

    private LocalFile authorized(String ticket, String fileId) {
        try {
            if (!Tokens.parseTicket(catalogKey.get(), ticket).fileId().equals(fileId)) {
                throw Status.PERMISSION_DENIED.withDescription("ticket is for another file").asRuntimeException();
            }
        } catch (JwtException | IllegalArgumentException e) {
            throw Status.PERMISSION_DENIED.withDescription("invalid or expired download ticket").asRuntimeException();
        }
        LocalFile f = store.get(fileId);
        if (f == null) {
            throw Status.NOT_FOUND.withDescription("file not on this peer").asRuntimeException();
        }
        return f;
    }
}
