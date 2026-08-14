package com.example.backend.debug;

final class DebugNotFoundException extends RuntimeException {

    DebugNotFoundException(String message) {
        super(message);
    }
}
