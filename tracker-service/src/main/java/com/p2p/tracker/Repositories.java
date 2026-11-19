package com.p2p.tracker;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

interface PeerRepository extends MongoRepository<PeerRecord, String> {
    List<PeerRecord> findByLastSeenAfter(Instant cutoff);
}

interface SwarmRepository extends MongoRepository<SwarmEntry, String> {
    List<SwarmEntry> findByFileIdAndHaveCountGreaterThan(String fileId, int haveCount);

    long deleteByPeerId(String peerId);

    long deleteByPeerIdNotIn(Collection<String> alivePeerIds);
}
