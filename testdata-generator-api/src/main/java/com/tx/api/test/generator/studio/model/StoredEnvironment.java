package com.tx.api.test.generator.studio.model;

import java.time.Instant;

public class StoredEnvironment {

    private String id;
    private String specId;
    private String name;
    private String documentJson;
    private Instant createdAt;
    private Instant updatedAt;
    /** Production safety flag (column {@code is_protected}); kept out of the document so export and import can't move it. */
    private boolean protectedEnvironment;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getSpecId() {
        return specId;
    }

    public void setSpecId(String specId) {
        this.specId = specId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDocumentJson() {
        return documentJson;
    }

    public void setDocumentJson(String documentJson) {
        this.documentJson = documentJson;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public boolean isProtectedEnvironment() {
        return protectedEnvironment;
    }

    public void setProtectedEnvironment(boolean protectedEnvironment) {
        this.protectedEnvironment = protectedEnvironment;
    }
}
