package com.tx.api.test.generator.studio.api;

import com.tx.api.test.generator.studio.dto.AddProtectedTargetRequest;
import com.tx.api.test.generator.studio.dto.ApprovalView;
import com.tx.api.test.generator.studio.dto.DecideApprovalRequest;
import com.tx.api.test.generator.studio.dto.EmergencyAccessView;
import com.tx.api.test.generator.studio.dto.EvaluateSafetyRequest;
import com.tx.api.test.generator.studio.dto.GrantEmergencyAccessRequest;
import com.tx.api.test.generator.studio.dto.PlanPreflightView;
import com.tx.api.test.generator.studio.dto.RequestApprovalRequest;
import com.tx.api.test.generator.studio.dto.RunPlanRequest;
import com.tx.api.test.generator.studio.dto.SafetyEvaluateView;
import com.tx.api.test.generator.studio.dto.EnvironmentProtectionView;
import com.tx.api.test.generator.studio.dto.SafetySettingsView;
import com.tx.api.test.generator.studio.dto.SafetySummaryView;
import com.tx.api.test.generator.studio.dto.SaveOperationRuleRequest;
import com.tx.api.test.generator.studio.dto.SetEnvironmentProtectionRequest;
import com.tx.api.test.generator.studio.dto.UpdateSafetySettingsRequest;
import com.tx.api.test.generator.studio.safety.ApprovalService;
import com.tx.api.test.generator.studio.safety.SafetyPolicyService;
import com.tx.api.test.generator.studio.service.SafetyPreviewService;
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
    private final ApprovalService approvalService;
    private final SafetyPreviewService previewService;

    public SafetyController(SafetyPolicyService safetyPolicyService, ApprovalService approvalService,
                            SafetyPreviewService previewService) {
        this.safetyPolicyService = safetyPolicyService;
        this.approvalService = approvalService;
        this.previewService = previewService;
    }

    // --- Previews (send nothing) ---

    @PostMapping("/safety/evaluate")
    public SafetyEvaluateView evaluate(@RequestBody EvaluateSafetyRequest request) {
        return previewService.evaluate(request);
    }

    @PostMapping("/runs/plan/preflight")
    public PlanPreflightView preflight(@RequestBody RunPlanRequest request) {
        return previewService.preflight(request);
    }

    // --- Approvals of one case (any member) ---

    @PostMapping("/testdata/{caseId}/approvals")
    public ApprovalView requestApproval(@PathVariable String caseId, @RequestBody RequestApprovalRequest request) {
        return approvalService.request(caseId, request);
    }

    @GetMapping("/testdata/{caseId}/approvals")
    public List<ApprovalView> caseApprovals(@PathVariable String caseId, @RequestParam String environmentId) {
        return approvalService.forCase(caseId, environmentId);
    }

    @DeleteMapping("/testdata/{caseId}/approvals/{approvalId}")
    public ApprovalView withdrawApproval(@PathVariable String caseId, @PathVariable String approvalId) {
        return approvalService.withdraw(caseId, approvalId);
    }

    // --- Approvals inbox and emergency access (organization owners and admins) ---

    @GetMapping("/organizations/current/safety/approvals")
    public List<ApprovalView> approvals(@RequestParam(required = false) String status) {
        return approvalService.list(status);
    }

    @PostMapping("/organizations/current/safety/approvals/{approvalId}/approve")
    public ApprovalView approve(@PathVariable String approvalId,
                                @RequestBody(required = false) DecideApprovalRequest request) {
        return approvalService.approve(approvalId, request);
    }

    @PostMapping("/organizations/current/safety/approvals/{approvalId}/reject")
    public ApprovalView reject(@PathVariable String approvalId,
                               @RequestBody(required = false) DecideApprovalRequest request) {
        return approvalService.reject(approvalId, request);
    }

    @PostMapping("/organizations/current/safety/approvals/{approvalId}/revoke")
    public ApprovalView revoke(@PathVariable String approvalId,
                               @RequestBody(required = false) DecideApprovalRequest request) {
        return approvalService.revoke(approvalId, request);
    }

    @GetMapping("/organizations/current/safety/emergency")
    public List<EmergencyAccessView> emergencyAccess() {
        return approvalService.currentEmergency();
    }

    @PostMapping("/organizations/current/safety/emergency")
    public EmergencyAccessView grantEmergencyAccess(@RequestBody GrantEmergencyAccessRequest request) {
        return approvalService.grantEmergency(request);
    }

    @PostMapping("/organizations/current/safety/emergency/{accessId}/confirm")
    public EmergencyAccessView confirmEmergencyAccess(@PathVariable String accessId) {
        return approvalService.confirmEmergency(accessId);
    }

    @DeleteMapping("/organizations/current/safety/emergency/{accessId}")
    public EmergencyAccessView endEmergencyAccess(@PathVariable String accessId) {
        return approvalService.endEmergency(accessId);
    }

    /** Emergency access in one environment (0 or 1 entries), for the banner every member sees. */
    @GetMapping("/safety/emergency")
    public List<EmergencyAccessView> environmentEmergencyAccess(@RequestParam String environmentId) {
        return approvalService.currentEmergencyFor(environmentId);
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
