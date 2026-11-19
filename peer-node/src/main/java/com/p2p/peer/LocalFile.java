package com.p2p.peer;

import com.p2p.common.Bitfields;
import com.p2p.proto.Manifest;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/** A file this peer is sharing or downloading: its manifest, which chunks are present, and live stats. */
final class LocalFile {
    enum Kind { SHARED, DOWNLOAD }

    /** Lifecycle, see docs/diagrams/06-download-lifecycle. */
    enum Status { SEEDING, QUEUED, FINDING_PEERS, DOWNLOADING, STALLED, PAUSED, VERIFYING, COMPLETED, FAILED }

    final Manifest manifest;
    final Kind kind;
    private final BitSet have;
    /** Per chunk: 0 = local / unknown source, k = sourceNames[k-1]. Only for the chunk map in the UI. */
    private final byte[] sources;
    private final List<String> sourceNames = new ArrayList<>();

    volatile Path dataPath;
    volatile Status status;
    volatile String error;
    /** Bitfield changed since the last Announce to the tracker. */
    volatile boolean dirty = true;
    volatile long speed;       // bytes/s
    volatile int activePeers;
    final AtomicLong downloaded = new AtomicLong();
    final AtomicLong uploaded = new AtomicLong();

    LocalFile(Manifest manifest, Kind kind, BitSet have, Path dataPath, Status status) {
        this.manifest = manifest;
        this.kind = kind;
        this.have = (BitSet) have.clone();
        this.sources = new byte[manifest.getChunkCount()];
        this.dataPath = dataPath;
        this.status = status;
    }

    String id() {
        return manifest.getFileId();
    }

    int chunkCount() {
        return manifest.getChunkCount();
    }

    long offset(int index) {
        return (long) index * manifest.getChunkSize();
    }

    int chunkLength(int index) {
        return (int) Math.min(manifest.getChunkSize(), manifest.getSizeBytes() - offset(index));
    }

    synchronized boolean has(int i) {
        return have.get(i);
    }

    synchronized void markHave(int i, String sourceName) {
        have.set(i);
        int slot = sourceNames.indexOf(sourceName);
        if (slot < 0 && sourceNames.size() < 255) {
            sourceNames.add(sourceName);
            slot = sourceNames.size() - 1;
        }
        sources[i] = (byte) (slot + 1);
        dirty = true;
    }

    synchronized void clear(int i) {
        have.clear(i);
        sources[i] = 0;
        dirty = true;
    }

    synchronized int haveCount() {
        return have.cardinality();
    }

    synchronized boolean complete() {
        return have.cardinality() == chunkCount();
    }

    synchronized BitSet haveCopy() {
        return (BitSet) have.clone();
    }

    synchronized byte[] bitfield() {
        return Bitfields.toBytes(have, chunkCount());
    }

    synchronized byte[] sourcesCopy() {
        return sources.clone();
    }

    synchronized List<String> sourceNamesCopy() {
        return List.copyOf(sourceNames);
    }
}
