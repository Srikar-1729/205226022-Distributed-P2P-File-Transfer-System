package com.p2p.peer.web;

import org.springframework.http.HttpStatus;

/** An error the UI should show as-is, with an HTTP status. */
class ApiException extends RuntimeException {
    final HttpStatus status;

    ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }
}
