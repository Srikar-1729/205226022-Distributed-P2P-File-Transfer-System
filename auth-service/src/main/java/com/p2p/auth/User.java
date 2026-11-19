package com.p2p.auth;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/** {@code auth_db.users}. The id is a MongoDB ObjectId (hex string). */
@Document("users")
public record User(
        @Id String id,
        @Indexed(name = "username_1", unique = true) String username,
        String passwordHash,
        Instant createdAt) {
}
