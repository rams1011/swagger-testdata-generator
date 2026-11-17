package com.tx.api.test.generator.studio.dto;

import java.util.List;

/** {@code target}: a host, a pattern such as {@code *.prod.acme.com}, an IP address or a CIDR range. */
public record AddProtectedTargetRequest(
        String target,
        List<Integer> ports,
        String note
) {
}
