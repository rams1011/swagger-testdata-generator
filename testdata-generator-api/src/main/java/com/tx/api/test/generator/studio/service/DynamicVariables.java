package com.tx.api.test.generator.studio.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** Postman-style {@code {{$name}}} values, generated fresh for every occurrence. */
public final class DynamicVariables {

    public static final List<String> NAMES = List.of(
            "$guid", "$randomUUID", "$timestamp", "$isoTimestamp", "$randomInt", "$randomBoolean",
            "$randomAlphaNumeric", "$randomFirstName", "$randomLastName", "$randomFullName", "$randomUserName",
            "$randomEmail", "$randomPhoneNumber", "$randomCity", "$randomCountry", "$randomWord");

    private static final String ALPHANUMERIC = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final List<String> FIRST_NAMES = List.of(
            "Olivia", "Liam", "Emma", "Noah", "Ava", "Ethan", "Mia", "Lucas", "Sofia", "Arjun", "Priya", "Mateo");
    private static final List<String> LAST_NAMES = List.of(
            "Smith", "Johnson", "Brown", "Garcia", "Miller", "Davis", "Martinez", "Wilson", "Patel", "Kumar",
            "Nguyen", "Lopez");
    private static final List<String> CITIES = List.of(
            "London", "Austin", "Toronto", "Sydney", "Berlin", "Hyderabad", "Dublin", "Lisbon", "Denver", "Osaka");
    private static final List<String> COUNTRIES = List.of(
            "United Kingdom", "United States", "Canada", "Australia", "Germany", "India", "Ireland", "Portugal",
            "Japan", "Brazil");
    private static final List<String> WORDS = List.of(
            "alpha", "bravo", "cobalt", "delta", "ember", "falcon", "garnet", "harbor", "indigo", "juniper");

    private DynamicVariables() {
    }

    public static boolean isDynamic(String name) {
        return name != null && name.startsWith("$");
    }

    public static String value(String name) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        return switch (name) {
            case "$guid", "$randomUUID" -> UUID.randomUUID().toString();
            case "$timestamp" -> String.valueOf(Instant.now().getEpochSecond());
            case "$isoTimestamp" -> Instant.now().truncatedTo(ChronoUnit.MILLIS).toString();
            case "$randomInt" -> String.valueOf(random.nextInt(0, 1001));
            case "$randomBoolean" -> String.valueOf(random.nextBoolean());
            case "$randomAlphaNumeric" -> String.valueOf(ALPHANUMERIC.charAt(random.nextInt(ALPHANUMERIC.length())));
            case "$randomFirstName" -> pick(FIRST_NAMES);
            case "$randomLastName" -> pick(LAST_NAMES);
            case "$randomFullName" -> pick(FIRST_NAMES) + " " + pick(LAST_NAMES);
            case "$randomUserName" -> userName();
            case "$randomEmail" -> userName() + "@example.com";
            case "$randomPhoneNumber" -> String.format(Locale.ROOT, "%03d-%03d-%04d",
                    random.nextInt(200, 1000), random.nextInt(0, 1000), random.nextInt(0, 10_000));
            case "$randomCity" -> pick(CITIES);
            case "$randomCountry" -> pick(COUNTRIES);
            case "$randomWord" -> pick(WORDS);
            default -> throw new IllegalArgumentException("Unknown dynamic variable {{" + name + "}}. Supported: "
                    + String.join(", ", NAMES) + ".");
        };
    }

    private static String userName() {
        return (pick(FIRST_NAMES) + "." + pick(LAST_NAMES)).toLowerCase(Locale.ROOT)
                + ThreadLocalRandom.current().nextInt(10, 1000);
    }

    private static String pick(List<String> values) {
        return values.get(ThreadLocalRandom.current().nextInt(values.size()));
    }
}
