package com.example.wgwarden.peer;

import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public record PeerUpdateRequest(
    @Size(max = 100) String name,
    Boolean enabled,
    String privateKey,
    @PositiveOrZero Long trafficLimitBytes,
    Instant expiresAt,
    Boolean clearTrafficLimit,
    Boolean clearExpiresAt
) {}
