package com.p2p.peer;

import com.p2p.proto.AuthResponse;
import com.p2p.proto.Empty;
import com.p2p.proto.FileId;
import com.p2p.proto.FileInfo;
import com.p2p.proto.SearchRequest;
import com.p2p.proto.SearchResponse;
import com.p2p.proto.UpdateAccessRequest;
import com.p2p.proto.Visibility;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Operations the web layer ({@code com.p2p.peer.web}) may call. Keeps the core classes package-private
 * while the controllers live in their own package.
 */
@Component
public class PeerApi {
    private final PeerNode node;

    PeerApi(PeerNode node) {
        this.node = node;
    }

    public boolean loggedIn() {
        return node.session.loggedIn();
    }

    public AuthResponse register(String user, String pass) {
        return node.register(user, pass);
    }

    public AuthResponse login(String user, String pass) {
        return node.login(user, pass);
    }

    public AuthResponse guest() {
        return node.guest();
    }

    public void logout() {
        node.logout();
    }

    public SearchResponse search(String q) {
        return node.catalog().searchFiles(SearchRequest.newBuilder().setQuery(q).build());
    }

    public SearchResponse myFiles() {
        return node.catalog().listMyFiles(Empty.getDefaultInstance());
    }

    public FileInfo updateAccess(String fileId, Visibility v, List<String> users) {
        return node.catalog().updateAccess(UpdateAccessRequest.newBuilder()
                .setFileId(fileId).setVisibility(v).addAllAllowedUsers(users).build());
    }

    public void delete(String fileId) {
        node.catalog().deleteFile(FileId.newBuilder().setFileId(fileId).build());
        node.forget(fileId);
    }

    public void download(String fileId) throws IOException {
        node.downloads.start(fileId);
    }

    public void pause(String fileId) {
        node.downloads.pause(fileId);
    }

    public void resume(String fileId) {
        node.downloads.resume(fileId);
    }

    public void remove(String fileId) {
        node.downloads.remove(fileId);
    }

    /** Streams the uploaded bytes into {@code shared/}, then chunks, hashes, registers and seeds the file. */
    public FileInfo share(String name, InputStream body, Visibility v, List<String> users) throws IOException {
        Path target = FileStore.uniqueTarget(node.store.sharedDir, name);
        Files.copy(body, target);
        try {
            return node.share(target, name, v, users);
        } catch (RuntimeException | IOException e) {
            Files.deleteIfExists(target);
            throw e;
        }
    }

    public record Download(String name, Path path) {}

    /** A completed or own file, for "Save to computer". */
    public Optional<Download> localFile(String fileId) {
        LocalFile f = node.store.get(fileId);
        if (f == null || !(f.status == LocalFile.Status.COMPLETED || f.status == LocalFile.Status.SEEDING)) {
            return Optional.empty();
        }
        return Optional.of(new Download(f.manifest.getName(), f.dataPath));
    }
}
