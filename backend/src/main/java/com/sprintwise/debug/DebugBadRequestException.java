package com.sprintwise.debug;

final class DebugBadRequestException extends RuntimeException {

    private final String code;

    DebugBadRequestException(String code, String message) {
        super(message);
        this.code = code;
    }

    String code() {
        return code;
    }
}
