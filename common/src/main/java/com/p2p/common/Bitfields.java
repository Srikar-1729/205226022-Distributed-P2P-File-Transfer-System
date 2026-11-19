package com.p2p.common;

import java.util.Arrays;
import java.util.BitSet;

/** A bitfield marks which chunks a peer has: bit i = chunk i (little-endian within each byte). */
public final class Bitfields {
    private Bitfields() {}

    public static byte[] toBytes(BitSet bits, int chunkCount) {
        return Arrays.copyOf(bits.toByteArray(), (chunkCount + 7) / 8);
    }

    public static BitSet fromBytes(byte[] bytes, int chunkCount) {
        BitSet b = BitSet.valueOf(bytes);
        if (b.length() > chunkCount) {
            b.clear(chunkCount, b.length());
        }
        return b;
    }

    public static BitSet full(int chunkCount) {
        BitSet b = new BitSet(chunkCount);
        b.set(0, chunkCount);
        return b;
    }
}
