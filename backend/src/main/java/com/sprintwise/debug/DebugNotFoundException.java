package com.sprintwise.debug;

final class DebugNotFoundException extends RuntimeException {

    DebugNotFoundException(String message) {
        super(message);
    }
}
