package com.p2p.peer.web;

import com.p2p.peer.PeerView;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.scheduling.annotation.Scheduled;

/** Server-Sent Events: pushes the whole UI state to every open browser tab twice a second. */
@Component
class StateView {
    private final PeerView view;
    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    StateView(PeerView view) {
        this.view = view;
    }

    SseEmitter subscribe() {
        SseEmitter e = new SseEmitter(0L); // never time out
        e.onCompletion(() -> emitters.remove(e));
        e.onTimeout(() -> emitters.remove(e));
        e.onError(t -> emitters.remove(e));
        emitters.add(e);
        send(e, view.snapshot());
        return e;
    }

    @Scheduled(fixedRate = 500)
    void broadcast() {
        if (emitters.isEmpty()) {
            return;
        }
        Map<String, Object> state = view.snapshot();
        emitters.forEach(e -> send(e, state));
    }

    private void send(SseEmitter e, Map<String, Object> state) {
        try {
            e.send(SseEmitter.event().data(state));
        } catch (IOException | IllegalStateException ex) {
            emitters.remove(e); // browser tab closed
        }
    }
}
