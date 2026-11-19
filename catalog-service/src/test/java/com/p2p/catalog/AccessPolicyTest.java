package com.p2p.catalog;

import com.p2p.common.Principal;
import com.p2p.proto.Role;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.p2p.proto.Visibility.PRIVATE;
import static com.p2p.proto.Visibility.PUBLIC;
import static com.p2p.proto.Visibility.REGISTERED;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The visibility matrix from docs/diagrams/07-access-matrix. */
class AccessPolicyTest {
    static final Principal ALICE = new Principal("u-alice", "alice", Role.USER); // owner
    static final Principal BOB = new Principal("u-bob", "bob", Role.USER);       // shared-with
    static final Principal CAROL = new Principal("u-carol", "carol", Role.USER); // other user
    static final Principal GUEST = new Principal("guest-1", "guest-1", Role.GUEST);
    static final List<String> SHARED = List.of("bob");

    @Test
    void publicFilesAreOpenToEveryone() {
        for (Principal p : List.of(ALICE, BOB, CAROL, GUEST)) {
            assertTrue(AccessPolicy.canAccess(PUBLIC, "u-alice", List.of(), p), p.username());
        }
    }

    @Test
    void registeredFilesExcludeGuests() {
        assertTrue(AccessPolicy.canAccess(REGISTERED, "u-alice", List.of(), ALICE));
        assertTrue(AccessPolicy.canAccess(REGISTERED, "u-alice", List.of(), CAROL));
        assertFalse(AccessPolicy.canAccess(REGISTERED, "u-alice", List.of(), GUEST));
    }

    @Test
    void privateFilesOnlyForOwnerAndSharedWith() {
        assertTrue(AccessPolicy.canAccess(PRIVATE, "u-alice", SHARED, ALICE));
        assertTrue(AccessPolicy.canAccess(PRIVATE, "u-alice", SHARED, BOB));
        assertFalse(AccessPolicy.canAccess(PRIVATE, "u-alice", SHARED, CAROL));
        assertFalse(AccessPolicy.canAccess(PRIVATE, "u-alice", SHARED, GUEST));
    }

    @Test
    void guestCannotBeAddedToPrivateList() {
        assertFalse(AccessPolicy.canAccess(PRIVATE, "u-alice", List.of("guest-1"), GUEST));
    }

    @Test
    void anonymousCallerGetsNothing() {
        assertFalse(AccessPolicy.canAccess(PUBLIC, "u-alice", List.of(), null));
    }
}
