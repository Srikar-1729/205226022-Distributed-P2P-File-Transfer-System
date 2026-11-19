package com.p2p.common;

import org.junit.jupiter.api.Test;

import java.util.BitSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BitfieldsTest {
    @Test
    void roundTripKeepsLengthEvenWhenTrailingBitsAreZero() {
        BitSet b = new BitSet();
        b.set(0);
        b.set(9);
        byte[] bytes = Bitfields.toBytes(b, 20);
        assertEquals(3, bytes.length);
        assertEquals(b, Bitfields.fromBytes(bytes, 20));
    }

    @Test
    void fullSetsExactlyChunkCountBits() {
        BitSet b = Bitfields.full(13);
        assertEquals(13, b.cardinality());
        assertTrue(b.get(12));
        assertEquals(Bitfields.full(13), Bitfields.fromBytes(Bitfields.toBytes(b, 13), 13));
    }
}
