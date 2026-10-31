package com.tx.api.test.generator.studio.storage;

import org.apache.commons.lang3.StringUtils;

import java.util.stream.Collectors;

final class SpecListFilterCacheKeys {

    private SpecListFilterCacheKeys() {
    }

    static String forFilter(SpecListFilter filter) {
        if (filter == null || !filter.paged()) {
            return "all";
        }
        String ids = filter.ids().stream().sorted().collect(Collectors.joining(","));
        return String.join("|",
                nullToDash(filter.q()),
                nullToDash(filter.label()),
                StringUtils.defaultString(filter.sort(), "updated"),
                Integer.toString(filter.offset()),
                Integer.toString(filter.limit()),
                ids,
                filter.manual() == null ? "-" : filter.manual().toString());
    }

    private static String nullToDash(String value) {
        return StringUtils.isBlank(value) ? "-" : value.trim();
    }
}
