package com.p2p.peer;

import com.p2p.peer.ChunkScheduler.Assignment;
import com.p2p.peer.ChunkScheduler.PeerState;
import org.junit.jupiter.api.Test;

import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkSchedulerTest {
    static BitSet bits(int from, int to) {
        BitSet b = new BitSet();
        b.set(from, to);
        return b;
    }

    @Test
    void rarestChunksAreRequestedFirst() {
        // A has 0-9, B has 5-19 -> chunks 0-4 and 10-19 have one copy, 5-9 have two
        List<PeerState> peers = List.of(new PeerState("A", bits(0, 10), 0), new PeerState("B", bits(5, 20), 0));
        List<Assignment> plan = ChunkScheduler.plan(20, new BitSet(), new HashMap<>(), peers, 4, 2, new Random(1));
        assertEquals(4, plan.size()); // 2 peers x 2 in flight
        for (Assignment a : plan) {
            assertTrue(a.chunk() < 5 || a.chunk() >= 10, "chunk " + a.chunk() + " is not one of the rarest");
        }
    }

    @Test
    void respectsPerPeerAndMaxPeerLimits() {
        List<PeerState> peers = List.of(
                new PeerState("A", bits(0, 50), 0), new PeerState("B", bits(0, 50), 0),
                new PeerState("C", bits(0, 50), 0), new PeerState("D", bits(0, 50), 0),
                new PeerState("E", bits(0, 50), 0), new PeerState("F", bits(0, 50), 0));
        List<Assignment> plan = ChunkScheduler.plan(50, new BitSet(), new HashMap<>(), peers, 4, 2, new Random(2));
        Map<String, Long> perPeer = plan.stream().collect(Collectors.groupingBy(Assignment::peerId, Collectors.counting()));
        assertEquals(4, perPeer.size(), "at most 4 peers in parallel");
        perPeer.values().forEach(n -> assertEquals(2L, n, "2 requests in flight per peer"));
    }

    @Test
    void skipsChunksWeHaveOrAlreadyRequested() {
        BitSet have = bits(0, 3);
        Map<Integer, Set<String>> inflight = new HashMap<>(Map.of(3, Set.of("A")));
        List<PeerState> peers = List.of(new PeerState("A", bits(0, 6), 1), new PeerState("B", bits(0, 6), 0));
        List<Assignment> plan = ChunkScheduler.plan(6, have, inflight, peers, 4, 2, new Random(3));
        assertEquals(Set.of(4, 5), plan.stream().map(Assignment::chunk).collect(Collectors.toSet()));
    }

    @Test
    void endgameDuplicatesLastRequestOnAnotherPeer() {
        BitSet have = bits(0, 9);
        Map<Integer, Set<String>> inflight = new HashMap<>(Map.of(9, Set.of("A")));
        List<PeerState> peers = List.of(new PeerState("A", bits(0, 10), 1), new PeerState("B", bits(0, 10), 0));
        List<Assignment> plan = ChunkScheduler.plan(10, have, inflight, peers, 4, 2, new Random(4));
        assertEquals(List.of(new Assignment(9, "B")), plan);
    }

    @Test
    void nothingToDoWhenNoPeerHasMissingChunks() {
        List<PeerState> peers = List.of(new PeerState("A", bits(0, 5), 0));
        assertTrue(ChunkScheduler.plan(10, bits(0, 5), new HashMap<>(), peers, 4, 2, new Random(5)).isEmpty());
    }
}
