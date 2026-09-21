package com.tx.api.test.generator.studio.tenant;

import java.time.Instant;

public class Subscription {

    private String id;
    private String orgId;
    private String planCode;
    private String status;
    private int seatLimit;
    private int workspaceLimit;
    private int specLimit;
    private int runLimitMonth;
    private Instant trialEndsAt;
    private Instant currentPeriodEnd;
    private Instant createdAt;

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

    public String getPlanCode() {
        return planCode;
    }

    public void setPlanCode(String planCode) {
        this.planCode = planCode;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public int getSeatLimit() {
        return seatLimit;
    }

    public void setSeatLimit(int seatLimit) {
        this.seatLimit = seatLimit;
    }

    public int getWorkspaceLimit() {
        return workspaceLimit;
    }

    public void setWorkspaceLimit(int workspaceLimit) {
        this.workspaceLimit = workspaceLimit;
    }

    public int getSpecLimit() {
        return specLimit;
    }

    public void setSpecLimit(int specLimit) {
        this.specLimit = specLimit;
    }

    public int getRunLimitMonth() {
        return runLimitMonth;
    }

    public void setRunLimitMonth(int runLimitMonth) {
        this.runLimitMonth = runLimitMonth;
    }

    public Instant getTrialEndsAt() {
        return trialEndsAt;
    }

    public void setTrialEndsAt(Instant trialEndsAt) {
        this.trialEndsAt = trialEndsAt;
    }

    public Instant getCurrentPeriodEnd() {
        return currentPeriodEnd;
    }

    public void setCurrentPeriodEnd(Instant currentPeriodEnd) {
        this.currentPeriodEnd = currentPeriodEnd;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
