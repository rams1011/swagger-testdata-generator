package com.tx.api.test.generator.studio.service;

import com.jayway.jsonpath.InvalidPathException;
import com.jayway.jsonpath.JsonPath;
import com.tx.api.test.generator.studio.collection.HeaderRules;
import com.tx.api.test.generator.studio.dto.RunAuth;
import com.tx.api.test.generator.studio.model.StudioTestCase;
import org.apache.commons.lang3.StringUtils;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * What a stored case may hold, checked on every save and import with the same rules the case editor applies, so a
 * case from the API or a collection file can't hold what the editor refuses (and then fail every run).
 */
public final class CaseRules {

    private static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern COOKIE_NAME = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]+");
    private static final Set<String> RESPONSE_TYPES = Set.of("JSON", "NONE", "STRING", "FILE", "XML", "TEXT", "NA");
    private static final Set<String> OPERATORS = Set.of("exists", "notexists", "nonempty", "empty", "matches", "range",
            "ne", "contains", "notcontains", "containsobject", "notcontainsobject", "gt", "gte", "lt", "lte", "type",
            "length");
    private static final Set<String> TYPES = Set.of("string", "number", "integer", "boolean", "object", "array",
            "null");

    private CaseRules() {
    }

    /** The checks on the case as it will be stored (after defaults are filled in). */
    public static void requireStorable(StudioTestCase testCase) {
        int status = testCase.getStatusCode();
        if (status < 100 || status > 599) {
            throw new IllegalArgumentException("Expected status must be a three-digit HTTP status from 100 to 599");
        }
        String responseType = StringUtils.trimToEmpty(testCase.getResponseType()).toUpperCase(Locale.ROOT);
        if (!responseType.isEmpty() && !RESPONSE_TYPES.contains(responseType)) {
            throw new IllegalArgumentException("Response type must be JSON, NONE, STRING or FILE, not '"
                    + testCase.getResponseType() + "'");
        }
        requireCookies(testCase.getCookieParams());
        requireExtracts(testCase.getExtracts());
        requireAssertions(testCase.getJsonPathAssertions());
    }

    /** A cookie name is a token; a value has no line breaks or control characters (they would break the request). */
    public static void requireCookies(Map<String, String> cookies) {
        String problem = cookieProblem(cookies);
        if (problem != null) {
            throw new IllegalArgumentException(problem);
        }
    }

    /** Why these cookies can't be sent, or null. Blank names are left out, as for headers. */
    public static String cookieProblem(Map<String, String> cookies) {
        if (cookies == null) {
            return null;
        }
        for (Map.Entry<String, String> cookie : cookies.entrySet()) {
            String name = cookie.getKey() == null ? "" : cookie.getKey().trim();
            if (name.isEmpty()) {
                continue;
            }
            if (!COOKIE_NAME.matcher(name).matches()) {
                return "Cookie '" + name + "' isn't a valid cookie name";
            }
            String value = cookie.getValue();
            if (value != null && value.indexOf(';') >= 0) {
                // A ; ends the cookie and starts another one; the editor refuses it too.
                return "Cookie '" + name + "' can't contain ; in its value";
            }
            if (value != null) {
                for (int index = 0; index < value.length(); index++) {
                    char character = value.charAt(index);
                    if ((character < 0x20 && character != '\t') || character == 0x7F) {
                        return "Cookie '" + name + "' has a line break or control character in its value";
                    }
                }
            }
        }
        return null;
    }

    /** Extract rules {@code name=source}: a variable name, and a JSONPath, {@code header:}, {@code cookie:} or {@code body:} source. */
    public static void requireExtracts(String extracts) {
        for (Map.Entry<String, String> rule : JsonPathEvaluator.parseExtracts(extracts).entrySet()) {
            String name = rule.getKey();
            if (!NAME.matcher(name).matches()) {
                throw new IllegalArgumentException("Extract name '" + name
                        + "' must start with a letter or _ and use only letters, digits and _");
            }
            String source = StringUtils.trimToEmpty(rule.getValue());
            if (source.isEmpty()) {
                throw new IllegalArgumentException("Extract '" + name + "' needs a source, such as $.id or header:Location");
            }
            String lower = source.toLowerCase(Locale.ROOT);
            if (lower.startsWith("header:") || lower.startsWith("cookie:")) {
                if (source.substring(source.indexOf(':') + 1).isBlank()) {
                    throw new IllegalArgumentException("Extract '" + name + "' needs a header or cookie name after the colon");
                }
                continue;
            }
            requireJsonPath("Extract '" + name + "'", lower.startsWith("body:") ? source.substring(5).trim() : source);
        }
    }

    /**
     * Response checks {@code path=expected}: a JSONPath (or an {@code xpath:}, {@code header:} or {@code cookie:}
     * check) and, for {@code op:} checks, a known operator with a usable argument.
     */
    public static void requireAssertions(String assertions) {
        for (Map.Entry<String, String> check : JsonPathEvaluator.parseAssertions(assertions).entrySet()) {
            String path = check.getKey();
            String label = "Check " + path;
            String lowerPath = path.toLowerCase(Locale.ROOT);
            if (!lowerPath.startsWith("xpath:") && !lowerPath.startsWith("header:") && !lowerPath.startsWith("cookie:")) {
                requireJsonPath(label, path);
            }
            String expected = StringUtils.defaultString(check.getValue());
            if (!expected.toLowerCase(Locale.ROOT).startsWith("op:")) {
                continue;
            }
            int colon = expected.indexOf(':', 3);
            String operator = (colon < 0 ? expected.substring(3) : expected.substring(3, colon)).toLowerCase(Locale.ROOT);
            String argument = colon < 0 ? "" : expected.substring(colon + 1);
            if (!OPERATORS.contains(operator)) {
                throw new IllegalArgumentException(label + ": unknown check 'op:" + operator + "'");
            }
            if (argument.contains("{{")) {
                continue;
            }
            switch (operator) {
                case "gt", "gte", "lt", "lte" -> requireNumber(label, argument);
                case "range" -> requireRange(label, argument);
                case "length" -> requireLength(label, argument);
                case "type" -> {
                    if (!TYPES.contains(argument.trim().toLowerCase(Locale.ROOT))) {
                        throw new IllegalArgumentException(label + ": type must be one of " + String.join(", ", TYPES.stream().sorted().toList()));
                    }
                }
                case "matches" -> {
                    try {
                        Pattern.compile(argument);
                    } catch (PatternSyntaxException invalid) {
                        throw new IllegalArgumentException(label + ": the pattern isn't a valid regular expression");
                    }
                }
                default -> {
                }
            }
        }
    }

    /** Body match: strict, ignoreOrder or lenient (or blank for strict); anything else is refused, not read as strict. */
    public static void requireResponseMatch(String value) {
        if (StringUtils.isBlank(value)) {
            return;
        }
        String key = value.replace("_", "").replace("-", "").replace(" ", "").toLowerCase(Locale.ROOT);
        if (!Set.of("strict", "exact", "ignoreorder", "anyorder", "unordered", "lenient", "subset", "contains")
                .contains(key)) {
            throw new IllegalArgumentException("Body match must be strict, ignoreOrder or lenient, not '" + value + "'");
        }
    }

    /** Case auth type: inherit, none, basic, bearer, API key or OAuth 2; an unknown type is refused, not read as none. */
    public static void requireAuthType(RunAuth auth) {
        if (auth == null || StringUtils.isBlank(auth.type())) {
            return;
        }
        String raw = auth.type().trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        if (!Set.of("INHERIT", "NONE", "CREDENTIALS", "BASIC_AUTH", "BASIC", "BEARER", "BEARER_TOKEN", "TOKEN",
                "API_KEY", "APIKEY", "KEY", "OAUTH2", "OAUTH", "OPENID", "OIDC").contains(raw)) {
            throw new IllegalArgumentException("Case auth type must be INHERIT, NONE, BASIC, BEARER, API_KEY or OAUTH2, not '"
                    + auth.type() + "'");
        }
    }

    /** Enabled: Y or N (true/false and yes/no are read the same); anything else is refused, not read as Y. */
    public static String enabledFlag(String value) {
        if (StringUtils.isBlank(value)) {
            return "Y";
        }
        return switch (value.trim().toUpperCase(Locale.ROOT)) {
            case "Y", "YES", "TRUE" -> "Y";
            case "N", "NO", "FALSE" -> "N";
            default -> throw new IllegalArgumentException("Enabled must be Y or N, not '" + value + "'");
        };
    }

    private static void requireJsonPath(String label, String path) {
        if (path.contains("{{")) {
            return;
        }
        if (!path.startsWith("$")) {
            throw new IllegalArgumentException(label + ": '" + path + "' isn't a JSONPath (it starts with $)");
        }
        try {
            JsonPath.compile(path);
        } catch (InvalidPathException | IllegalArgumentException invalid) {
            throw new IllegalArgumentException(label + ": '" + path + "' isn't a valid JSONPath");
        }
    }

    private static void requireNumber(String label, String argument) {
        try {
            new BigDecimal(argument.trim());
        } catch (NumberFormatException notNumber) {
            throw new IllegalArgumentException(label + ": enter a number to compare with, not '" + argument + "'");
        }
    }

    private static void requireRange(String label, String argument) {
        int dots = argument.indexOf("..");
        if (dots < 0) {
            throw new IllegalArgumentException(label + ": a range is written min..max, such as 1..10");
        }
        String low = argument.substring(0, dots).trim();
        String high = argument.substring(dots + 2).trim();
        if (!low.isEmpty()) {
            requireNumber(label, low);
        }
        if (!high.isEmpty()) {
            requireNumber(label, high);
        }
    }

    private static void requireLength(String label, String argument) {
        String text = argument.trim();
        if (text.contains("..")) {
            requireRange(label, text);
            return;
        }
        if (!text.matches("\\d+")) {
            throw new IllegalArgumentException(label + ": length is a whole number or a range such as 1..5");
        }
    }
}
