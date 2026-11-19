package com.p2p.peer;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

import java.nio.file.Path;

/** {@code peer.*} settings (application.yml, overridable with {@code --peer.xxx=...}). */
@ConfigurationProperties("peer")
public record PeerProperties(
        int index,
        String name,
        @DefaultValue("localhost") String host,      // address other peers use to reach this one
        Path dataDir,
        @DefaultValue("0") DataSize uploadLimit,     // per second; 0 = unlimited
        @DefaultValue("4") int maxPeers,             // peers downloaded from in parallel
        @DefaultValue("2") int perPeer,              // requests in flight per peer
        @DefaultValue("false") boolean debugCorrupt, // demo: serve corrupted chunks
        @DefaultValue("certs/ca.crt") Path caCert) { // trusted when connecting to other peers
}
