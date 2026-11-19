package com.p2p.tracker;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/** {@code tracker_db.swarm}: which chunks of one file one peer has. Id = {@code fileId:peerId}. */
@Document("swarm")
@CompoundIndex(name = "fileId_1_peerId_1", def = "{'fileId': 1, 'peerId': 1}", unique = true)
public record SwarmEntry(
        @Id String id,
        @Indexed(name = "fileId_1") String fileId,
        String peerId,
        byte[] bitfield,
        int chunkCount,
        int haveCount,
        Instant updatedAt) {
}
