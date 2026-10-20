package com.drewdrew0414.domain.group;

import jakarta.validation.constraints.*;
import java.util.Set;

public final class GroupRequests {
    private GroupRequests() {}

    public record Write(@NotBlank @Size(max=16) String name,
                        @NotNull @Size(max=1000) String description) {}
    public record MemberRole(@NotNull @Positive Long roleId) {}
    public record RoleWrite(@NotBlank @Size(max=32) String name,
                            @NotNull Set<@NotNull GroupPermission> permissions) {}
}
