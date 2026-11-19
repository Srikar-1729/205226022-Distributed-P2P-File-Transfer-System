package com.p2p.catalog;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface FileRepository extends MongoRepository<FileRecord, String> {
    List<FileRecord> findByOwnerIdOrderByCreatedAtDesc(String ownerId);
}
