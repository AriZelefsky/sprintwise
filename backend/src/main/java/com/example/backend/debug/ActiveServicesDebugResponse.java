package com.example.backend.debug;

import com.example.backend.model.FeedScopedId;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

public record ActiveServicesDebugResponse(
    String feedId,
    LocalDate date,
    List<String> activeServiceIds
) {
    static ActiveServicesDebugResponse from(
        String feedId,
        LocalDate date,
        Set<FeedScopedId> activeServiceIds
    ) {
        return new ActiveServicesDebugResponse(
            feedId,
            date,
            activeServiceIds.stream().map(FeedScopedId::toString).toList()
        );
    }

    public ActiveServicesDebugResponse {
        activeServiceIds = List.copyOf(activeServiceIds);
    }
}
