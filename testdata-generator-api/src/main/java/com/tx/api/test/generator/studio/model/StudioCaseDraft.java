package com.tx.api.test.generator.studio.model;

import java.time.Instant;

/** Unpublished changes to a published case. The case itself stays as published until they are published. */
public class StudioCaseDraft {

    private String testCaseId;
    private String documentJson;
    private Long baseRevision;
    private long revision = 1;
    private Instant updatedAt;
    private String updatedByLabel;

    public String getTestCaseId() {
        return testCaseId;
    }

    public void setTestCaseId(String testCaseId) {
        this.testCaseId = testCaseId;
    }

    /** The save request these changes came from, as JSON. */
    public String getDocumentJson() {
        return documentJson;
    }

    public void setDocumentJson(String documentJson) {
        this.documentJson = documentJson;
    }

    /** The revision of the published version these changes started from; publishing checks it hasn't moved on. */
    public Long getBaseRevision() {
        return baseRevision;
    }

    public void setBaseRevision(Long baseRevision) {
        this.baseRevision = baseRevision;
    }

    /** Increases by one each time these changes are saved. */
    public long getRevision() {
        return revision;
    }

    public void setRevision(long revision) {
        this.revision = revision;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public String getUpdatedByLabel() {
        return updatedByLabel;
    }

    public void setUpdatedByLabel(String updatedByLabel) {
        this.updatedByLabel = updatedByLabel;
    }
}
