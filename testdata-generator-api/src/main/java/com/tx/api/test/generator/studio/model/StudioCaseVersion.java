package com.tx.api.test.generator.studio.model;

import java.time.Instant;

/** A copy of a case as it was published. */
public record StudioCaseVersion(String id, Instant publishedAt, String publishedByLabel, String documentJson) {
}
