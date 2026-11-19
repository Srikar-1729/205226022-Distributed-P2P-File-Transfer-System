package com.p2p.peer.web;

import com.p2p.peer.PeerApi;
import com.p2p.peer.PeerView;
import com.p2p.proto.Visibility;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * JSON API for this peer's browser UI (localhost only). The browser talks to its own node over HTTP;
 * the node talks to everything else over gRPC.
 */
@RestController
@RequestMapping("/api")
class PeerController {
    private final PeerApi api;
    private final PeerView view;
    private final StateView events;

    PeerController(PeerApi api, PeerView view, StateView events) {
        this.api = api;
        this.view = view;
        this.events = events;
    }

    record Credentials(String username, String password) {}

    record FileRef(String fileId) {}

    record Access(String fileId, String visibility, String allowedUsers) {}

    @GetMapping("/state")
    Map<String, Object> state() {
        return view.snapshot();
    }

    @GetMapping(path = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter events() {
        return events.subscribe();
    }

    @PostMapping("/register")
    Map<String, Object> register(@RequestBody Credentials c) {
        return PeerView.session(api.register(c.username(), c.password()));
    }

    @PostMapping("/login")
    Map<String, Object> login(@RequestBody Credentials c) {
        return PeerView.session(api.login(c.username(), c.password()));
    }

    @PostMapping("/guest")
    Map<String, Object> guest() {
        return PeerView.session(api.guest());
    }

    @PostMapping("/logout")
    Map<String, Object> logout() {
        api.logout();
        return ok();
    }

    /**
     * Raw file bytes in the body; name/visibility/allowedUsers in the query string. The query is parsed by hand
     * so the servlet container never tries to read the (up to 1 GB) body as form parameters.
     */
    @PostMapping("/share")
    Map<String, Object> share(HttpServletRequest request) throws IOException {
        requireLogin();
        var q = UriComponentsBuilder.newInstance().query(request.getQueryString()).build().getQueryParams();
        String name = decode(q.getFirst("name"));
        name = Path.of(name == null ? "file" : name).getFileName().toString().replaceAll("[^\\w.\\- ()]", "_");
        if (name.isBlank() || name.startsWith(".")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "bad file name");
        }
        String vis = decode(q.getFirst("visibility"));
        try (InputStream body = request.getInputStream()) {
            return view.fileInfo(api.share(name, body, visibility(vis == null ? "PUBLIC" : vis), users(decode(q.getFirst("allowedUsers")))));
        }
    }

    @GetMapping("/search")
    Map<String, Object> search(@RequestParam(defaultValue = "") String q) {
        requireLogin();
        return view.files(api.search(q));
    }

    @GetMapping("/my-files")
    Map<String, Object> myFiles() {
        requireLogin();
        return view.files(api.myFiles());
    }

    @PostMapping("/access")
    Map<String, Object> access(@RequestBody Access a) {
        requireLogin();
        return view.fileInfo(api.updateAccess(a.fileId(), visibility(a.visibility()), users(a.allowedUsers())));
    }

    @PostMapping("/delete")
    Map<String, Object> delete(@RequestBody FileRef f) {
        requireLogin();
        api.delete(f.fileId());
        return ok();
    }

    @PostMapping("/download")
    Map<String, Object> download(@RequestBody FileRef f) throws IOException {
        requireLogin();
        api.download(f.fileId());
        return ok();
    }

    @PostMapping("/pause")
    Map<String, Object> pause(@RequestBody FileRef f) {
        requireLogin();
        api.pause(f.fileId());
        return ok();
    }

    @PostMapping("/resume")
    Map<String, Object> resume(@RequestBody FileRef f) {
        requireLogin();
        api.resume(f.fileId());
        return ok();
    }

    @PostMapping("/remove")
    Map<String, Object> remove(@RequestBody FileRef f) {
        requireLogin();
        api.remove(f.fileId());
        return ok();
    }

    /** "Save to computer" for a completed download or an own shared file. */
    @GetMapping("/file")
    ResponseEntity<Resource> file(@RequestParam String id) {
        return api.localFile(id)
                .map(d -> ResponseEntity.ok()
                        .header(HttpHeaders.CONTENT_DISPOSITION,
                                ContentDisposition.attachment().filename(d.name(), StandardCharsets.UTF_8).build().toString())
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .<Resource>body(new FileSystemResource(d.path())))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    // ------------------------------------------------------------------ helpers

    private void requireLogin() {
        if (!api.loggedIn()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "log in or continue as guest first");
        }
    }

    private static Map<String, Object> ok() {
        return Map.of("ok", true);
    }

    private static String decode(String s) {
        return s == null ? null : URLDecoder.decode(s, StandardCharsets.UTF_8);
    }

    private static Visibility visibility(String s) {
        try {
            return Visibility.valueOf(s.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "visibility must be PUBLIC, REGISTERED or PRIVATE");
        }
    }

    private static List<String> users(String csv) {
        if (csv == null) {
            return List.of();
        }
        return Arrays.stream(csv.split("[,\\s]+")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}
