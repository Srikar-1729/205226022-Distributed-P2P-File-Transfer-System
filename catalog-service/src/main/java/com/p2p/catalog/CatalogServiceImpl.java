package com.p2p.catalog;

import com.google.protobuf.ByteString;
import com.p2p.common.AuthInterceptor;
import com.p2p.common.Hashes;
import com.p2p.common.Principal;
import com.p2p.common.Tokens;
import com.p2p.proto.CatalogServiceGrpc;
import com.p2p.proto.DownloadGrant;
import com.p2p.proto.Empty;
import com.p2p.proto.FileId;
import com.p2p.proto.FileInfo;
import com.p2p.proto.Manifest;
import com.p2p.proto.PublicKey;
import com.p2p.proto.RegisterFileRequest;
import com.p2p.proto.SearchRequest;
import com.p2p.proto.SearchResponse;
import com.p2p.proto.UpdateAccessRequest;
import com.p2p.proto.Visibility;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.server.service.GrpcService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.util.unit.DataSize;

import java.security.KeyPair;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/** gRPC CatalogService: file manifests + access control + Download Tickets. */
@GrpcService
class CatalogServiceImpl extends CatalogServiceGrpc.CatalogServiceImplBase {
    private static final Logger log = LoggerFactory.getLogger(CatalogServiceImpl.class);
    private static final Duration TICKET_TTL = Duration.ofMinutes(30);
    private static final int MAX_CHUNK = 4 * 1024 * 1024;

    private final FileRepository files;
    private final MongoTemplate mongo;
    private final KeyPair keys;
    private final long maxFileSize;

    CatalogServiceImpl(FileRepository files, MongoTemplate mongo, KeyPair keys,
                       @Value("${p2p.max-file-size}") DataSize maxFileSize) {
        this.files = files;
        this.mongo = mongo;
        this.keys = keys;
        this.maxFileSize = maxFileSize.toBytes();
    }

    @Override
    public void registerFile(RegisterFileRequest req, StreamObserver<FileInfo> out) {
        Principal p = AuthInterceptor.require();
        Manifest m = req.getManifest();
        validate(m);
        Visibility v = checkVisibility(p, req.getVisibility());
        FileRecord f = files.insert(new FileRecord(
                UUID.randomUUID().toString(), m.getName().trim(), m.getSizeBytes(), m.getChunkSize(), m.getChunkCount(),
                m.getChunkHashesList().stream().map(h -> Hashes.hex(h.toByteArray())).toList(),
                Hashes.hex(m.getFileHash().toByteArray()),
                p.id(), p.username(), p.role().name(), v, normalize(req.getAllowedUsersList(), v), Instant.now()));
        log.info("{} registered '{}' ({} bytes, {} chunks, {})", p.username(), f.name(), f.sizeBytes(), f.chunkCount(), v);
        out.onNext(toInfo(f, p));
        out.onCompleted();
    }

    @Override
    public void searchFiles(SearchRequest req, StreamObserver<SearchResponse> out) {
        Principal p = AuthInterceptor.require();
        Criteria c = AccessPolicy.visibleTo(p);
        String q = req.getQuery().trim();
        if (!q.isEmpty()) {
            c = new Criteria().andOperator(c, Criteria.where("name").regex(Pattern.quote(q), "i"));
        }
        List<FileRecord> found = mongo.find(Query.query(c).with(Sort.by(Sort.Direction.DESC, "createdAt")).limit(200), FileRecord.class);
        out.onNext(toResponse(found, p));
        out.onCompleted();
    }

    @Override
    public void listMyFiles(Empty req, StreamObserver<SearchResponse> out) {
        Principal p = AuthInterceptor.require();
        out.onNext(toResponse(files.findByOwnerIdOrderByCreatedAtDesc(p.id()), p));
        out.onCompleted();
    }

    @Override
    public void updateAccess(UpdateAccessRequest req, StreamObserver<FileInfo> out) {
        Principal p = AuthInterceptor.require();
        FileRecord f = ownedFile(req.getFileId(), p);
        Visibility v = checkVisibility(p, req.getVisibility());
        f = files.save(f.withAccess(v, normalize(req.getAllowedUsersList(), v)));
        log.info("{} changed access of {} to {}", p.username(), f.name(), v);
        out.onNext(toInfo(f, p));
        out.onCompleted();
    }

    @Override
    public void deleteFile(FileId req, StreamObserver<Empty> out) {
        Principal p = AuthInterceptor.require();
        FileRecord f = ownedFile(req.getFileId(), p);
        files.delete(f);
        log.info("{} deleted {}", p.username(), f.name());
        out.onNext(Empty.getDefaultInstance());
        out.onCompleted();
    }

    @Override
    public void authorizeDownload(FileId req, StreamObserver<DownloadGrant> out) {
        Principal p = AuthInterceptor.require();
        FileRecord f = files.findById(req.getFileId())
                .orElseThrow(() -> Status.NOT_FOUND.withDescription("file not found").asRuntimeException());
        if (!AccessPolicy.canAccess(f.visibility(), f.ownerId(), f.allowedUsers(), p)) {
            throw Status.PERMISSION_DENIED.withDescription("you are not allowed to download this file").asRuntimeException();
        }
        out.onNext(DownloadGrant.newBuilder()
                .setManifest(toManifest(f))
                .setTicket(Tokens.issueTicket(keys.getPrivate(), p.id(), f.id(), TICKET_TTL))
                .setExpiresAt(Instant.now().plus(TICKET_TTL).toEpochMilli())
                .build());
        out.onCompleted();
    }

    @Override
    public void getPublicKey(Empty req, StreamObserver<PublicKey> out) {
        out.onNext(PublicKey.newBuilder().setDer(ByteString.copyFrom(keys.getPublic().getEncoded())).setAlgorithm("RSA").build());
        out.onCompleted();
    }

    // ------------------------------------------------------------------ helpers

    private void validate(Manifest m) {
        String why = null;
        if (m.getName().isBlank()) why = "name is required";
        else if (m.getSizeBytes() <= 0) why = "empty files cannot be shared";
        else if (m.getSizeBytes() > maxFileSize) why = "file is larger than the limit of " + DataSize.ofBytes(maxFileSize).toMegabytes() + " MB";
        else if (m.getChunkSize() <= 0 || m.getChunkSize() > MAX_CHUNK) why = "bad chunk size";
        else if (m.getChunkCount() != (int) ((m.getSizeBytes() + m.getChunkSize() - 1) / m.getChunkSize())) why = "chunk count does not match size";
        else if (m.getChunkHashesCount() != m.getChunkCount()) why = "one hash per chunk is required";
        else if (m.getFileHash().size() != 32 || m.getChunkHashesList().stream().anyMatch(h -> h.size() != 32)) why = "hashes must be SHA-256";
        if (why != null) {
            throw Status.INVALID_ARGUMENT.withDescription(why).asRuntimeException();
        }
    }

    private static Visibility checkVisibility(Principal p, Visibility v) {
        if (v == Visibility.VISIBILITY_UNSPECIFIED || v == Visibility.UNRECOGNIZED) {
            v = Visibility.PUBLIC;
        }
        if (p.isGuest() && v != Visibility.PUBLIC) {
            throw Status.INVALID_ARGUMENT.withDescription("guests can only share PUBLIC files - log in to restrict access").asRuntimeException();
        }
        return v;
    }

    private static List<String> normalize(List<String> users, Visibility v) {
        if (v != Visibility.PRIVATE) {
            return List.of();
        }
        return users.stream().map(u -> u.trim().toLowerCase(Locale.ROOT)).filter(u -> !u.isEmpty()).distinct().toList();
    }

    private FileRecord ownedFile(String fileId, Principal p) {
        FileRecord f = files.findById(fileId)
                .orElseThrow(() -> Status.NOT_FOUND.withDescription("file not found").asRuntimeException());
        if (!p.id().equals(f.ownerId())) {
            throw Status.PERMISSION_DENIED.withDescription("only the owner can do that").asRuntimeException();
        }
        return f;
    }

    private static SearchResponse toResponse(List<FileRecord> list, Principal p) {
        SearchResponse.Builder b = SearchResponse.newBuilder();
        list.forEach(f -> b.addFiles(toInfo(f, p)));
        return b.build();
    }

    private static FileInfo toInfo(FileRecord f, Principal p) {
        return FileInfo.newBuilder()
                .setFileId(f.id())
                .setName(f.name())
                .setSizeBytes(f.sizeBytes())
                .setChunkCount(f.chunkCount())
                .setOwnerName(f.ownerName())
                .setVisibility(f.visibility())
                .addAllAllowedUsers(f.allowedUsers())
                .setCreatedAt(f.createdAt().toEpochMilli())
                .setOwnedByMe(p.id().equals(f.ownerId()))
                .build();
    }

    private static Manifest toManifest(FileRecord f) {
        Manifest.Builder m = Manifest.newBuilder()
                .setFileId(f.id())
                .setName(f.name())
                .setSizeBytes(f.sizeBytes())
                .setChunkSize(f.chunkSize())
                .setChunkCount(f.chunkCount())
                .setFileHash(ByteString.copyFrom(Hashes.unhex(f.fileHash())));
        f.chunkHashes().forEach(h -> m.addChunkHashes(ByteString.copyFrom(Hashes.unhex(h))));
        return m.build();
    }
}
