package com.p2p.peer;

import com.p2p.proto.CatalogServiceGrpc;
import com.p2p.proto.DownloadGrant;
import com.p2p.proto.FileId;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/** Download Tickets per file, renewed via {@code Catalog.AuthorizeDownload} two minutes before they expire. */
final class TicketCache {
    private static final long RENEW_BEFORE_MS = 120_000;
    private final Supplier<CatalogServiceGrpc.CatalogServiceBlockingStub> catalog;
    private final Map<String, DownloadGrant> grants = new ConcurrentHashMap<>();

    TicketCache(Supplier<CatalogServiceGrpc.CatalogServiceBlockingStub> catalog) {
        this.catalog = catalog;
    }

    DownloadGrant grant(String fileId) {
        DownloadGrant g = grants.get(fileId);
        if (g == null || g.getExpiresAt() - System.currentTimeMillis() < RENEW_BEFORE_MS) {
            g = catalog.get().authorizeDownload(FileId.newBuilder().setFileId(fileId).build());
            grants.put(fileId, g);
        }
        return g;
    }

    String ticket(String fileId) {
        return grant(fileId).getTicket();
    }

    void clear() {
        grants.clear();
    }
}
