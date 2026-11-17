package com.tx.api.test.generator.studio.safety;

import java.time.Instant;
import java.util.List;

/** A production host, host pattern ({@code *.prod.acme.com}) or IP range. Empty {@code ports} means any port. */
public record ProtectedTarget(
        String id,
        TargetKind kind,
        String pattern,
        List<Integer> ports,
        String note,
        String createdBy,
        Instant createdAt
) {
    public ProtectedTarget {
        ports = ports == null ? List.of() : List.copyOf(ports);
    }

    public boolean coversPort(int port) {
        return ports.isEmpty() || ports.contains(port);
    }
}
