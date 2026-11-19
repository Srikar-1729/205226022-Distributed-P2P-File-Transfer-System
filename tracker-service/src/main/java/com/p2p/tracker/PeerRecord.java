package com.p2p.tracker;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * {@code tracker_db.peers}: one online peer. The TTL index deletes it 30 s after its last heartbeat
 * (MongoDB checks TTLs once a minute, so queries also filter on {@code lastSeen}).
 */
@Document("peers")
public record PeerRecord(
        @Id String id,
        String name,
        String principal,
        String username,
        String host,
        int port,
        @Indexed(name = "lastSeen_1", expireAfter = "30s") Instant lastSeen) {
}
