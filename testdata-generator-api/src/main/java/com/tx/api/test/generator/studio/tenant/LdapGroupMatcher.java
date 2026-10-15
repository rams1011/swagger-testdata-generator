package com.tx.api.test.generator.studio.tenant;

import org.apache.commons.lang3.StringUtils;

import java.util.List;
import java.util.Locale;

/**
 * Matches configured group names against directory {@code memberOf} values, which may be
 * full DNs, partial {@code CN=name} RDNs, or bare common names.
 */
public final class LdapGroupMatcher {

    private LdapGroupMatcher() {
    }

    public static boolean matchesAny(List<String> memberOfValues, String configuredGroup) {
        if (StringUtils.isBlank(configuredGroup)) {
            return true;
        }
        if (memberOfValues == null || memberOfValues.isEmpty()) {
            return false;
        }
        String spec = configuredGroup.trim();
        for (String memberOf : memberOfValues) {
            if (matches(memberOf, spec)) {
                return true;
            }
        }
        return false;
    }

    static boolean matches(String memberOf, String configured) {
        if (StringUtils.isAnyBlank(memberOf, configured)) {
            return false;
        }
        String left = memberOf.trim();
        String right = configured.trim();
        if (left.equalsIgnoreCase(right)) {
            return true;
        }
        String memberKey = groupKey(left);
        String configKey = groupKey(right);
        if (StringUtils.isNotBlank(memberKey) && memberKey.equalsIgnoreCase(configKey)) {
            return true;
        }
        String compactMember = compact(left);
        String compactConfig = compact(right);
        if (compactMember.contains(compactConfig) || compactConfig.contains(compactMember)) {
            return true;
        }
        return false;
    }

    /** Common name or normalized token used for configured group and memberOf DN. */
    static String groupKey(String value) {
        if (StringUtils.isBlank(value)) {
            return "";
        }
        String trimmed = value.trim();
        if (!trimmed.contains("=")) {
            return trimmed;
        }
        String cn = commonName(trimmed);
        return cn == null ? trimmed : cn;
    }

    private static String commonName(String dn) {
        String firstRdn = dn;
        int comma = dn.indexOf(',');
        if (comma > 0) {
            firstRdn = dn.substring(0, comma);
        }
        int equals = firstRdn.indexOf('=');
        if (equals < 0) {
            return firstRdn.trim();
        }
        String attribute = firstRdn.substring(0, equals).trim();
        if ("cn".equalsIgnoreCase(attribute)) {
            return firstRdn.substring(equals + 1).trim();
        }
        return firstRdn.substring(equals + 1).trim();
    }

    private static String compact(String value) {
        return value.toLowerCase(Locale.ROOT).replace(" ", "");
    }
}
