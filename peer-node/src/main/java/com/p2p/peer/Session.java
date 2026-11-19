package com.p2p.peer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.p2p.proto.AuthResponse;
import com.p2p.proto.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** The user logged in on this peer node. Saved to {@code session.json} so a restarted node logs back in and resumes. */
@Component
class Session {
    private static final Logger log = LoggerFactory.getLogger(Session.class);
    private final Path file;
    private final ObjectMapper json = new ObjectMapper();
    private volatile AuthResponse current;

    Session(PeerProperties props) {
        this.file = props.dataDir().resolve("session.json");
    }

    @SuppressWarnings("unchecked")
    void load() {
        if (!Files.exists(file)) {
            return;
        }
        try {
            Map<String, Object> m = json.readValue(file.toFile(), Map.class);
            AuthResponse r = AuthResponse.newBuilder()
                    .setToken((String) m.get("token"))
                    .setUserId((String) m.get("userId"))
                    .setUsername((String) m.get("username"))
                    .setRole(Role.valueOf((String) m.get("role")))
                    .setExpiresAt(((Number) m.get("expiresAt")).longValue())
                    .build();
            if (r.getExpiresAt() > System.currentTimeMillis() + 60_000) {
                current = r;
                log.info("restored session of {}", r.getUsername());
            }
        } catch (IOException | RuntimeException e) {
            log.warn("ignoring unreadable session file: {}", e.getMessage());
        }
    }

    void save(AuthResponse r) {
        current = r;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("token", r.getToken());
        m.put("userId", r.getUserId());
        m.put("username", r.getUsername());
        m.put("role", r.getRole().name());
        m.put("expiresAt", r.getExpiresAt());
        try {
            json.writeValue(file.toFile(), m);
        } catch (IOException e) {
            log.warn("could not save session: {}", e.getMessage());
        }
    }

    void clear() {
        current = null;
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // nothing useful to do
        }
    }

    boolean loggedIn() {
        AuthResponse r = current;
        return r != null && r.getExpiresAt() > System.currentTimeMillis();
    }

    String token() {
        return loggedIn() ? current.getToken() : null;
    }

    String username() {
        return loggedIn() ? current.getUsername() : null;
    }

    boolean isGuest() {
        return loggedIn() && current.getRole() == Role.GUEST;
    }
}
