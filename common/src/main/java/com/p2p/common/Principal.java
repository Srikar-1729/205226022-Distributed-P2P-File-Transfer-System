package com.p2p.common;

import com.p2p.proto.Role;

/** The authenticated caller, taken from a verified JWT. Guests have ids like {@code guest-<uuid>}. */
public record Principal(String id, String username, Role role) {
    public boolean isGuest() {
        return role == Role.GUEST;
    }
}
