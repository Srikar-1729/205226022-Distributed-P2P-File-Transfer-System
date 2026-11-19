package com.p2p.catalog;

import com.p2p.proto.Visibility;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;

/** {@code catalog_db.files}: one shared file's manifest plus its access settings. */
@Document("files")
public record FileRecord(
        @Id String id,
        @Indexed(name = "name_1") String name,
        long sizeBytes,
        int chunkSize,
        int chunkCount,
        List<String> chunkHashes, // SHA-256 hex, one per chunk
        String fileHash,          // SHA-256 hex of the whole file
        @Indexed(name = "ownerId_1") String ownerId,
        String ownerName,
        String ownerRole,
        Visibility visibility,
        List<String> allowedUsers,
        Instant createdAt) {

    FileRecord withAccess(Visibility v, List<String> users) {
        return new FileRecord(id, name, sizeBytes, chunkSize, chunkCount, chunkHashes, fileHash,
                ownerId, ownerName, ownerRole, v, users, createdAt);
    }
}
