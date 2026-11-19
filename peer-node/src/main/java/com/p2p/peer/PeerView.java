package com.p2p.peer;

import com.p2p.proto.AuthResponse;
import com.p2p.proto.FileInfo;
import com.p2p.proto.SearchResponse;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Turns the peer's internal state and gRPC replies into the JSON shapes the browser UI expects. */
@Component
public class PeerView {
    private final PeerNode node;

    PeerView(PeerNode node) {
        this.node = node;
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> s = new LinkedHashMap<>();
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("name", node.name);
        n.put("peerId", node.peerId);
        n.put("uiPort", node.uiPort);
        n.put("grpcPort", node.grpcPort);
        n.put("tls", node.tls);
        n.put("uploadLimit", node.uploadLimiter.bytesPerSecond());
        n.put("trackerOnline", node.trackerOnline);
        n.put("trackerError", node.trackerError);
        n.put("dataDir", node.dataDir.toAbsolutePath().toString());
        s.put("node", n);
        Map<String, Object> u = new LinkedHashMap<>();
        u.put("loggedIn", node.session.loggedIn());
        u.put("username", node.session.username());
        u.put("guest", node.session.isGuest());
        s.put("session", u);
        List<Map<String, Object>> files = new ArrayList<>();
        node.store.all().stream().sorted(Comparator.comparing((LocalFile f) -> f.manifest.getName())).forEach(f -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("fileId", f.id());
            m.put("name", f.manifest.getName());
            m.put("size", f.manifest.getSizeBytes());
            m.put("kind", f.kind.name());
            m.put("status", f.status.name());
            m.put("error", f.error);
            m.put("have", f.haveCount());
            m.put("chunkCount", f.chunkCount());
            m.put("bitfield", Base64.getEncoder().encodeToString(f.bitfield()));
            m.put("sources", Base64.getEncoder().encodeToString(f.sourcesCopy()));
            m.put("sourceNames", f.sourceNamesCopy());
            m.put("speed", f.speed);
            m.put("activePeers", f.activePeers);
            m.put("downloaded", f.downloaded.get());
            m.put("uploaded", f.uploaded.get());
            m.put("path", f.dataPath.toAbsolutePath().toString());
            files.add(m);
        });
        s.put("files", files);
        return s;
    }

    public static Map<String, Object> session(AuthResponse r) {
        return Map.of("username", r.getUsername(), "role", r.getRole().name());
    }

    public Map<String, Object> files(SearchResponse r) {
        List<Map<String, Object>> list = new ArrayList<>();
        r.getFilesList().forEach(f -> list.add(fileInfo(f)));
        return Map.of("files", list);
    }

    public Map<String, Object> fileInfo(FileInfo f) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("fileId", f.getFileId());
        m.put("name", f.getName());
        m.put("size", f.getSizeBytes());
        m.put("chunkCount", f.getChunkCount());
        m.put("owner", f.getOwnerName());
        m.put("visibility", f.getVisibility().name());
        m.put("allowedUsers", f.getAllowedUsersList());
        m.put("createdAt", f.getCreatedAt());
        m.put("ownedByMe", f.getOwnedByMe());
        LocalFile local = node.store.get(f.getFileId());
        m.put("localStatus", local == null ? null : local.status.name());
        return m;
    }
}
