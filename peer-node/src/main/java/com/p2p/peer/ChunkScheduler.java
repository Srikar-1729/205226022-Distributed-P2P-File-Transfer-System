package com.p2p.peer;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Decides which chunk to request from which peer (pure logic, unit tested).
 * <ol>
 *   <li><b>Rarest first</b>: chunks held by the fewest peers are requested first, so they spread
 *       before their only holder leaves.</li>
 *   <li>Each chunk goes to the <b>least-busy</b> peer that has it.</li>
 *   <li>Limits: at most {@code maxPeers} peers in use, at most {@code perPeer} requests in flight per peer.</li>
 *   <li><b>Endgame</b>: when every remaining chunk is already requested, duplicate the last few
 *       requests to another peer so one slow peer can't hold up the finish.</li>
 * </ol>
 */
final class ChunkScheduler {
    static final int ENDGAME_THRESHOLD = 4;

    record PeerState(String id, BitSet have, int inflight) {}

    record Assignment(int chunk, String peerId) {}

    private ChunkScheduler() {}

    static List<Assignment> plan(int chunkCount, BitSet have, Map<Integer, Set<String>> inflight,
                                 List<PeerState> peers, int maxPeers, int perPeer, Random rnd) {
        List<PeerState> order = new ArrayList<>(peers);
        Collections.shuffle(order, rnd); // random tie-break between equally loaded peers
        Map<String, Integer> load = new HashMap<>();
        Set<String> active = new HashSet<>();
        int[] copies = new int[chunkCount];
        for (PeerState p : order) {
            load.put(p.id(), p.inflight());
            if (p.inflight() > 0) {
                active.add(p.id());
            }
            for (int i = p.have().nextSetBit(0); i >= 0 && i < chunkCount; i = p.have().nextSetBit(i + 1)) {
                copies[i]++;
            }
        }

        List<Integer> wanted = new ArrayList<>();
        for (int i = 0; i < chunkCount; i++) {
            if (!have.get(i) && !inflight.containsKey(i) && copies[i] > 0) {
                wanted.add(i);
            }
        }
        Collections.shuffle(wanted, rnd);
        wanted.sort(Comparator.comparingInt(i -> copies[i])); // stable: rarest first, random among equals

        List<Assignment> out = new ArrayList<>();
        for (int chunk : wanted) {
            PeerState p = choose(chunk, order, load, active, maxPeers, perPeer, Set.of());
            if (p != null) {
                out.add(new Assignment(chunk, p.id()));
                load.merge(p.id(), 1, Integer::sum);
                active.add(p.id());
            }
        }

        boolean allRequested = wanted.isEmpty() && !inflight.isEmpty();
        if (allRequested && inflight.size() <= ENDGAME_THRESHOLD) {
            for (Map.Entry<Integer, Set<String>> e : inflight.entrySet()) {
                if (e.getValue().size() == 1 && !have.get(e.getKey())) {
                    PeerState p = choose(e.getKey(), order, load, active, maxPeers, perPeer, e.getValue());
                    if (p != null) {
                        out.add(new Assignment(e.getKey(), p.id()));
                        load.merge(p.id(), 1, Integer::sum);
                        active.add(p.id());
                    }
                }
            }
        }
        return out;
    }

    private static PeerState choose(int chunk, List<PeerState> peers, Map<String, Integer> load, Set<String> active,
                                    int maxPeers, int perPeer, Set<String> exclude) {
        PeerState best = null;
        for (PeerState p : peers) {
            int l = load.get(p.id());
            boolean canUse = active.contains(p.id()) || active.size() < maxPeers;
            if (p.have().get(chunk) && !exclude.contains(p.id()) && l < perPeer && canUse
                    && (best == null || l < load.get(best.id()))) {
                best = p;
            }
        }
        return best;
    }
}
