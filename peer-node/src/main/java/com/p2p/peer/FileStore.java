package com.p2p.peer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.ByteString;
import com.p2p.common.Bitfields;
import com.p2p.common.Hashes;
import com.p2p.proto.Manifest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.BitSet;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The peer's disk: {@code shared/} (files this user shares), {@code downloads/} ({@code <fileId>.part} while
 * downloading, then the real name) and {@code state/} ({@code <fileId>.manifest.json} + {@code <fileId>.state.json},
 * the latter rewritten atomically after every verified chunk so downloads survive crashes).
 */
@Component
class FileStore {
    private static final Logger log = LoggerFactory.getLogger(FileStore.class);
    static final int CHUNK_SIZE = 1024 * 1024;

    final Path sharedDir;
    final Path downloadDir;
    private final Path stateDir;
    private final Map<String, LocalFile> files = new ConcurrentHashMap<>();
    private final ObjectMapper json = new ObjectMapper();

    FileStore(PeerProperties props) {
        Path root = props.dataDir();
        sharedDir = root.resolve("shared");
        downloadDir = root.resolve("downloads");
        stateDir = root.resolve("state");
        try {
            Files.createDirectories(sharedDir);
            Files.createDirectories(downloadDir);
            Files.createDirectories(stateDir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    LocalFile get(String fileId) {
        return files.get(fileId);
    }

    Collection<LocalFile> all() {
        return files.values();
    }

    void add(LocalFile f) {
        files.put(f.id(), f);
        saveManifest(f);
        saveState(f);
    }

    void remove(String fileId) {
        LocalFile f = files.remove(fileId);
        try {
            Files.deleteIfExists(stateDir.resolve(fileId + ".manifest.json"));
            Files.deleteIfExists(stateDir.resolve(fileId + ".state.json"));
            if (f != null && f.kind == LocalFile.Kind.DOWNLOAD && f.status != LocalFile.Status.COMPLETED) {
                Files.deleteIfExists(f.dataPath);
            }
        } catch (IOException e) {
            log.warn("cleanup of {} failed: {}", fileId, e.getMessage());
        }
    }

    // ------------------------------------------------------------------ chunks

    byte[] readChunk(LocalFile f, int index) throws IOException {
        byte[] buf = new byte[f.chunkLength(index)];
        try (FileChannel ch = FileChannel.open(f.dataPath, StandardOpenOption.READ)) {
            ByteBuffer bb = ByteBuffer.wrap(buf);
            long pos = f.offset(index);
            while (bb.hasRemaining()) {
                int n = ch.read(bb, pos + bb.position());
                if (n < 0) {
                    throw new IOException("unexpected end of file");
                }
            }
        }
        return buf;
    }

    void writeChunk(LocalFile f, int index, byte[] data) throws IOException {
        try (FileChannel ch = FileChannel.open(f.dataPath, StandardOpenOption.WRITE)) {
            ByteBuffer bb = ByteBuffer.wrap(data);
            long pos = f.offset(index);
            while (bb.hasRemaining()) {
                ch.write(bb, pos + bb.position());
            }
        }
    }

    boolean verifyChunk(LocalFile f, int index) {
        try {
            return Arrays.equals(Hashes.sha256(readChunk(f, index)), f.manifest.getChunkHashes(index).toByteArray());
        } catch (IOException e) {
            return false;
        }
    }

    static void preallocate(Path p, long size) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(p.toFile(), "rw")) {
            raf.setLength(size);
        }
    }

    static byte[] hashFile(Path p) throws IOException {
        MessageDigest md = Hashes.sha256();
        byte[] buf = new byte[256 * 1024];
        try (InputStream in = Files.newInputStream(p)) {
            for (int n; (n = in.read(buf)) > 0; ) {
                md.update(buf, 0, n);
            }
        }
        return md.digest();
    }

    /** Splits a file into 1 MiB chunks and hashes each one plus the whole file (Share Manager). */
    static Manifest buildManifest(Path p, String name) throws IOException {
        long size = Files.size(p);
        int count = (int) ((size + CHUNK_SIZE - 1) / CHUNK_SIZE);
        Manifest.Builder m = Manifest.newBuilder().setName(name).setSizeBytes(size)
                .setChunkSize(CHUNK_SIZE).setChunkCount(count);
        MessageDigest whole = Hashes.sha256();
        byte[] buf = new byte[CHUNK_SIZE];
        try (InputStream in = Files.newInputStream(p)) {
            for (int i = 0; i < count; i++) {
                int len = in.readNBytes(buf, 0, CHUNK_SIZE);
                MessageDigest chunk = Hashes.sha256();
                chunk.update(buf, 0, len);
                whole.update(buf, 0, len);
                m.addChunkHashes(ByteString.copyFrom(chunk.digest()));
            }
        }
        return m.setFileHash(ByteString.copyFrom(whole.digest())).build();
    }

    void completeDownload(LocalFile f) throws IOException {
        Path target = uniqueTarget(downloadDir, f.manifest.getName());
        Files.move(f.dataPath, target, StandardCopyOption.ATOMIC_MOVE);
        f.dataPath = target;
        f.status = LocalFile.Status.COMPLETED;
        saveState(f);
    }

    static Path uniqueTarget(Path dir, String name) {
        Path p = dir.resolve(name);
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; Files.exists(p); i++) {
            p = dir.resolve(base + " (" + i + ")" + ext);
        }
        return p;
    }

    // ------------------------------------------------------------------ persistence

    private void saveManifest(LocalFile f) {
        Manifest m = f.manifest;
        List<String> hashes = new ArrayList<>();
        m.getChunkHashesList().forEach(h -> hashes.add(Hashes.hex(h.toByteArray())));
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("fileId", m.getFileId());
        doc.put("name", m.getName());
        doc.put("sizeBytes", m.getSizeBytes());
        doc.put("chunkSize", m.getChunkSize());
        doc.put("chunkCount", m.getChunkCount());
        doc.put("fileHash", Hashes.hex(m.getFileHash().toByteArray()));
        doc.put("kind", f.kind.name());
        doc.put("chunkHashes", hashes);
        writeAtomically(stateDir.resolve(f.id() + ".manifest.json"), doc);
    }

    void saveState(LocalFile f) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("status", f.status.name());
        doc.put("dataPath", f.dataPath.toAbsolutePath().toString());
        doc.put("bitfield", Base64.getEncoder().encodeToString(f.bitfield()));
        doc.put("updatedAt", System.currentTimeMillis());
        synchronized (f) {
            writeAtomically(stateDir.resolve(f.id() + ".state.json"), doc);
        }
    }

    private void writeAtomically(Path target, Object doc) {
        try {
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            json.writeValue(tmp.toFile(), doc);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write " + target, e);
        }
    }

    /** Restores every file from {@code state/}; re-hashes chunks of unfinished downloads (catches half-written data). */
    @SuppressWarnings("unchecked")
    void loadAll() {
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(stateDir, "*.manifest.json")) {
            for (Path mf : ds) {
                try {
                    Map<String, Object> m = json.readValue(mf.toFile(), Map.class);
                    String id = (String) m.get("fileId");
                    Map<String, Object> s = json.readValue(stateDir.resolve(id + ".state.json").toFile(), Map.class);
                    Manifest.Builder mb = Manifest.newBuilder().setFileId(id).setName((String) m.get("name"))
                            .setSizeBytes(((Number) m.get("sizeBytes")).longValue())
                            .setChunkSize(((Number) m.get("chunkSize")).intValue())
                            .setChunkCount(((Number) m.get("chunkCount")).intValue())
                            .setFileHash(ByteString.copyFrom(Hashes.unhex((String) m.get("fileHash"))));
                    for (String h : (List<String>) m.get("chunkHashes")) {
                        mb.addChunkHashes(ByteString.copyFrom(Hashes.unhex(h)));
                    }
                    Manifest manifest = mb.build();
                    LocalFile.Kind kind = LocalFile.Kind.valueOf((String) m.get("kind"));
                    LocalFile.Status status = LocalFile.Status.valueOf((String) s.get("status"));
                    Path data = Path.of((String) s.get("dataPath"));
                    BitSet have = Bitfields.fromBytes(Base64.getDecoder().decode((String) s.get("bitfield")), manifest.getChunkCount());
                    restore(manifest, kind, status, data, have);
                } catch (Exception e) {
                    log.warn("skipping unreadable state {}: {}", mf.getFileName(), e.toString());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void restore(Manifest m, LocalFile.Kind kind, LocalFile.Status status, Path data, BitSet have) throws IOException {
        boolean finished = kind == LocalFile.Kind.SHARED || status == LocalFile.Status.COMPLETED;
        if (finished) {
            if (!Files.isRegularFile(data) || Files.size(data) != m.getSizeBytes()) {
                log.warn("{} was moved or changed on disk - no longer sharing it", data);
                remove(m.getFileId());
                return;
            }
            files.put(m.getFileId(), new LocalFile(m, kind, Bitfields.full(m.getChunkCount()), data,
                    kind == LocalFile.Kind.SHARED ? LocalFile.Status.SEEDING : LocalFile.Status.COMPLETED));
            return;
        }
        if (!Files.exists(data)) {
            preallocate(data, m.getSizeBytes());
            have.clear();
        }
        LocalFile.Status next = status == LocalFile.Status.PAUSED ? LocalFile.Status.PAUSED : LocalFile.Status.QUEUED;
        LocalFile f = new LocalFile(m, kind, have, data, next);
        int before = have.cardinality();
        for (int i = have.nextSetBit(0); i >= 0; i = have.nextSetBit(i + 1)) {
            if (!verifyChunk(f, i)) {
                f.clear(i);
            }
        }
        log.info("resuming '{}': {}/{} chunks verified on disk ({} discarded)", m.getName(), f.haveCount(),
                m.getChunkCount(), before - f.haveCount());
        files.put(m.getFileId(), f);
        saveState(f);
    }
}
