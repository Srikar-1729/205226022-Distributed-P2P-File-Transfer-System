package com.p2p.peer;

import com.p2p.peer.LocalFile.Kind;
import com.p2p.peer.LocalFile.Status;
import com.p2p.proto.DownloadGrant;
import com.p2p.proto.Manifest;

import java.io.IOException;
import java.nio.file.Path;
import java.util.BitSet;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Starts, pauses and resumes {@link DownloadTask}s - one per file. */
final class DownloadManager {
    private final PeerNode node;
    private final Map<String, DownloadTask> running = new ConcurrentHashMap<>();
    private final ExecutorService threads = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "download");
        t.setDaemon(true);
        return t;
    });

    DownloadManager(PeerNode node) {
        this.node = node;
    }

    synchronized LocalFile start(String fileId) throws IOException {
        LocalFile f = node.store.get(fileId);
        if (f != null) {
            if (f.kind == Kind.DOWNLOAD && f.status != Status.COMPLETED) {
                launch(f);
            }
            return f;
        }
        DownloadGrant g = node.tickets.grant(fileId); // Catalog checks access here
        Manifest m = g.getManifest();
        Path part = node.store.downloadDir.resolve(fileId + ".part");
        FileStore.preallocate(part, m.getSizeBytes());
        f = new LocalFile(m, Kind.DOWNLOAD, new BitSet(), part, Status.QUEUED);
        node.store.add(f);
        launch(f);
        return f;
    }

    synchronized void pause(String fileId) {
        LocalFile f = node.store.get(fileId);
        if (f == null || f.kind != Kind.DOWNLOAD || f.status == Status.COMPLETED) {
            return;
        }
        DownloadTask t = running.remove(fileId);
        if (t != null) {
            t.requestStop(Status.PAUSED);
        }
        f.status = Status.PAUSED;
        f.speed = 0;
        node.store.saveState(f);
    }

    synchronized void resume(String fileId) {
        LocalFile f = node.store.get(fileId);
        if (f != null && f.kind == Kind.DOWNLOAD && f.status != Status.COMPLETED) {
            f.status = Status.QUEUED;
            launch(f);
        }
    }

    synchronized void remove(String fileId) {
        DownloadTask t = running.remove(fileId);
        if (t != null) {
            t.requestStop(Status.PAUSED);
        }
        node.store.remove(fileId);
    }

    /** After login: continue every unfinished download that wasn't paused by the user. */
    synchronized void resumeAll() {
        for (LocalFile f : node.store.all()) {
            if (f.kind == Kind.DOWNLOAD && f.status != Status.COMPLETED && f.status != Status.PAUSED) {
                f.status = Status.QUEUED;
                launch(f);
            }
        }
    }

    /** On logout / shutdown: stop all tasks but keep them queued for the next login. */
    synchronized void stopAll() {
        running.values().forEach(t -> t.requestStop(Status.QUEUED));
        running.clear();
    }

    private void launch(LocalFile f) {
        if (running.containsKey(f.id())) {
            return;
        }
        DownloadTask t = new DownloadTask(node, f);
        running.put(f.id(), t);
        threads.submit(() -> {
            try {
                t.run();
            } finally {
                running.remove(f.id(), t);
            }
        });
    }
}
