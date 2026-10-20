package com.drewdrew0414.domain.group;

import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class ContestRequests {
    private ContestRequests() {}

    public record Write(@NotBlank @Size(max=200) String title,
                        @NotNull Instant startsAt,@NotNull Instant endsAt,
                        @NotEmpty @Size(max=50) List<@NotNull @Positive Integer> problemNumbers,
                        boolean replayAllowed,boolean captureProtection,boolean windowSwitchProtection) {}
    public enum EventType {
        WINDOW_BLUR,VISIBILITY_HIDDEN,FULLSCREEN_EXIT,COPY_BLOCKED,PRINT_BLOCKED,CAPTURE_KEY;
        boolean windowSwitch() {
            return this==WINDOW_BLUR || this==VISIBILITY_HIDDEN || this==FULLSCREEN_EXIT;
        }
    }
    public record Event(@NotNull EventType type,@NotNull UUID eventId) {}
}
