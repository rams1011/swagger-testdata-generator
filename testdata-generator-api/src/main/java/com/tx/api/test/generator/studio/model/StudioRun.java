package com.tx.api.test.generator.studio.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class StudioRun {

    private String id;
    private String specId;
    private String planId;
    private String environmentId;
    private String trigger;
    private int passed;
    private int failed;
    private int skipped;
    private Instant startedAt;
    private List<StudioRunResult> results = new ArrayList<>();

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

    public String getPlanId() {
        return planId;
    }

    public void setPlanId(String planId) {
        this.planId = planId;
    }

    public String getEnvironmentId() {
        return environmentId;
    }

    public void setEnvironmentId(String environmentId) {
        this.environmentId = environmentId;
    }

    public String getTrigger() {
        return trigger;
    }

    public void setTrigger(String trigger) {
        this.trigger = trigger;
    }

    public int getPassed() {
        return passed;
    }

    public void setPassed(int passed) {
        this.passed = passed;
    }

    public int getFailed() {
        return failed;
    }

    public void setFailed(int failed) {
        this.failed = failed;
    }

    public int getSkipped() {
        return skipped;
    }

    public void setSkipped(int skipped) {
        this.skipped = skipped;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public List<StudioRunResult> getResults() {
        return results;
    }

    public void setResults(List<StudioRunResult> results) {
        this.results = results == null ? new ArrayList<>() : results;
    }
}
