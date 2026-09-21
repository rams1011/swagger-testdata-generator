package com.tx.api.test.generator.studio.tenant;

import java.time.Instant;

public class Tenant {

    private String id;
    private String orgId;
    private String slug;
    private String name;
    private Instant createdAt;
    private boolean ready;
    private Instant enabledAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getOrgId() {
        return orgId;
    }

    public void setOrgId(String orgId) {
        this.orgId = orgId;
    }

    public String getSlug() {
        return slug;
    }

    public void setSlug(String slug) {
        this.slug = slug;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public boolean isReady() {
        return ready;
    }

    public void setReady(boolean ready) {
        this.ready = ready;
    }

    public Instant getEnabledAt() {
        return enabledAt;
    }

    public void setEnabledAt(Instant enabledAt) {
        this.enabledAt = enabledAt;
    }
}
