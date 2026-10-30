package com.tx.api.test.generator.studio.storage;

import org.apache.commons.lang3.StringUtils;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public record SpecListFilter(
        String q,
        String label,
        String sort,
        int offset,
        int limit,
        List<String> ids,
        Boolean manual
) {

    public static SpecListFilter of(String q, String label, String sort, Integer offset, Integer limit, String idsCsv,
                                    Boolean manual) {
        List<String> ids = StringUtils.isBlank(idsCsv)
                ? List.of()
                : Arrays.stream(idsCsv.split(","))
                .map(String::trim)
                .filter(StringUtils::isNotBlank)
                .distinct()
                .toList();
        int safeLimit = limit == null ? 0 : Math.min(Math.max(limit, 1), 100);
        int safeOffset = offset == null ? 0 : Math.max(offset, 0);
        String safeSort = StringUtils.defaultIfBlank(sort, "updated").trim().toLowerCase(Locale.ROOT);
        if (!safeSort.equals("name") && !safeSort.equals("updated")) {
            safeSort = "updated";
        }
        return new SpecListFilter(
                StringUtils.trimToNull(q),
                StringUtils.trimToNull(label),
                safeSort,
                safeOffset,
                safeLimit,
                ids,
                manual
        );
    }

    public boolean paged() {
        return limit > 0
                || StringUtils.isNotBlank(q)
                || StringUtils.isNotBlank(label)
                || !ids.isEmpty()
                || manual != null;
    }
}
