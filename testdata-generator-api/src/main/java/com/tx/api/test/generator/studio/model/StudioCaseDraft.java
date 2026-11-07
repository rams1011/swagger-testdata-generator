package com.tx.api.test.generator.studio.model;

import java.time.Instant;

/** Unpublished changes to a published case. The case itself stays as published until they are published. */
public class StudioCaseDraft {

    private String testCaseId;
    private String documentJson;
    private Instant baseUpdatedAt;
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

    /** When the published version these changes started from was saved; publishing checks it hasn't moved on. */
    public Instant getBaseUpdatedAt() {
        return baseUpdatedAt;
    }

    public void setBaseUpdatedAt(Instant baseUpdatedAt) {
        this.baseUpdatedAt = baseUpdatedAt;
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
