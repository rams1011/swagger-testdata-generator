package com.tx.api.test.generator.studio.api;

import com.tx.api.test.generator.studio.dto.AddProtectedTargetRequest;
import com.tx.api.test.generator.studio.dto.EnvironmentProtectionView;
import com.tx.api.test.generator.studio.dto.SafetySettingsView;
import com.tx.api.test.generator.studio.dto.SafetySummaryView;
import com.tx.api.test.generator.studio.dto.SaveOperationRuleRequest;
import com.tx.api.test.generator.studio.dto.SetEnvironmentProtectionRequest;
import com.tx.api.test.generator.studio.dto.UpdateSafetySettingsRequest;
import com.tx.api.test.generator.studio.safety.SafetyPolicyService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Production safety (UI repo docs/PRODUCTION_SAFETY.md). The service checks every role. */
@RestController
@RequestMapping("/api")
public class SafetyController {

    private final SafetyPolicyService safetyPolicyService;

    public SafetyController(SafetyPolicyService safetyPolicyService) {
        this.safetyPolicyService = safetyPolicyService;
    }

    @GetMapping("/safety/summary")
    public SafetySummaryView summary() {
        return safetyPolicyService.summary();
    }

    @GetMapping("/safety/environments")
    public List<EnvironmentProtectionView> environments(@RequestParam String specId) {
        return safetyPolicyService.environmentProtection(specId);
    }

    @PutMapping("/environments/{environmentId}/protection")
    public EnvironmentProtectionView setProtection(@PathVariable String environmentId,
                                                   @RequestBody SetEnvironmentProtectionRequest request) {
        return safetyPolicyService.setEnvironmentProtection(environmentId, request);
    }

    @GetMapping("/organizations/current/safety")
    public SafetySettingsView settings() {
        return safetyPolicyService.settings();
    }

    @PutMapping("/organizations/current/safety")
    public SafetySettingsView updateSettings(@RequestBody UpdateSafetySettingsRequest request) {
        return safetyPolicyService.updateSettings(request);
    }

    @PostMapping("/organizations/current/safety/targets")
    public SafetySettingsView addTarget(@RequestBody AddProtectedTargetRequest request) {
        return safetyPolicyService.addTarget(request);
    }

    @DeleteMapping("/organizations/current/safety/targets/{targetId}")
    public SafetySettingsView deleteTarget(@PathVariable String targetId) {
        return safetyPolicyService.deleteTarget(targetId);
    }

    @PostMapping("/organizations/current/safety/rules")
    public SafetySettingsView addRule(@RequestBody SaveOperationRuleRequest request) {
        return safetyPolicyService.addRule(request);
    }

    @PutMapping("/organizations/current/safety/rules/{ruleId}")
    public SafetySettingsView updateRule(@PathVariable String ruleId, @RequestBody SaveOperationRuleRequest request) {
        return safetyPolicyService.updateRule(ruleId, request);
    }

    @DeleteMapping("/organizations/current/safety/rules/{ruleId}")
    public SafetySettingsView deleteRule(@PathVariable String ruleId) {
        return safetyPolicyService.deleteRule(ruleId);
    }
}
