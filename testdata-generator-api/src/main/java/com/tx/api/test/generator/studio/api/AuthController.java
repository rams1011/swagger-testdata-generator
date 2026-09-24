package com.tx.api.test.generator.studio.api;

import com.tx.api.test.generator.studio.config.StudioProperties;
import com.tx.api.test.generator.studio.dto.AddMemberRequest;
import com.tx.api.test.generator.studio.dto.AuthSessionView;
import com.tx.api.test.generator.studio.dto.CreateTenantRequest;
import com.tx.api.test.generator.studio.dto.LoginRequest;
import com.tx.api.test.generator.studio.dto.MemberView;
import com.tx.api.test.generator.studio.dto.RegisterRequest;
import com.tx.api.test.generator.studio.dto.SwitchTenantRequest;
import com.tx.api.test.generator.studio.dto.SubscriptionView;
import com.tx.api.test.generator.studio.dto.TenantView;
import com.tx.api.test.generator.studio.dto.UpdateMemberRequest;
import com.tx.api.test.generator.studio.dto.UpdateSubscriptionRequest;
import com.tx.api.test.generator.studio.tenant.AuthService;
import com.tx.api.test.generator.studio.tenant.ForbiddenException;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;

@RestController
@RequestMapping("/api")
public class AuthController {

    private final StudioProperties properties;
    private final AuthService authService;

    public AuthController(StudioProperties properties, AuthService authService) {
        this.properties = properties;
        this.authService = authService;
    }

    @PostMapping("/auth/register")
    public AuthSessionView register(@RequestBody RegisterRequest request) {
        requireBilling();
        return authService.register(request);
    }

    @PostMapping("/auth/login")
    public AuthSessionView login(@RequestBody LoginRequest request) {
        requireAuth();
        return authService.login(request);
    }

    @GetMapping("/auth/session")
    public AuthSessionView session(HttpServletRequest request) {
        if (!properties.isAuthEnabled()) {
            return authService.localSession();
        }
        return authService.session(bearerToken(request));
    }

    @PostMapping("/auth/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(HttpServletRequest request) {
        authService.logout(bearerToken(request));
    }

    @PostMapping("/auth/switch-tenant")
    public AuthSessionView switchTenant(HttpServletRequest request, @RequestBody SwitchTenantRequest body) {
        requireAuth();
        if (body == null || StringUtils.isBlank(body.tenantId())) {
            throw new IllegalArgumentException("tenantId is required");
        }
        return authService.switchTenant(bearerToken(request), body.tenantId());
    }

    @PostMapping("/tenants")
    public TenantView createTenant(HttpServletRequest request, @RequestBody CreateTenantRequest body) {
        requireAuth();
        return authService.createWorkspace(bearerToken(request), body);
    }

    @GetMapping("/tenants/current/members")
    public List<MemberView> listMembers() {
        requireAuth();
        return authService.listMembers();
    }

    @PostMapping("/tenants/current/members")
    @ResponseStatus(HttpStatus.CREATED)
    public MemberView addMember(@RequestBody AddMemberRequest request) {
        requireAuth();
        return authService.addMember(request);
    }

    @PutMapping("/tenants/current/members/{accountId}")
    public MemberView updateMember(@PathVariable String accountId, @RequestBody UpdateMemberRequest request) {
        requireAuth();
        return authService.updateMember(accountId, request);
    }

    @DeleteMapping("/tenants/current/members/{accountId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeMember(@PathVariable String accountId) {
        requireAuth();
        authService.removeMember(accountId);
    }

    @GetMapping("/saas/subscription")
    public SubscriptionView currentSubscription() {
        requireBilling();
        return authService.currentSubscription();
    }

    @GetMapping("/saas/subscriptions")
    public List<SubscriptionView> listSubscriptions() {
        requireBilling();
        return authService.listSubscriptions();
    }

    @PutMapping("/saas/subscriptions/{orgId}")
    public SubscriptionView updateSubscription(@PathVariable String orgId,
                                               @RequestBody UpdateSubscriptionRequest request) {
        requireBilling();
        return authService.updateSubscription(orgId, request);
    }

    private void requireAuth() {
        if (!properties.isAuthEnabled()) {
            throw new ForbiddenException("Login is off. Set TESTDATA_EDITION=datacenter or saas.");
        }
    }

    private void requireBilling() {
        if (!properties.isBillingEnabled()) {
            throw new ForbiddenException("Plans and self-service signup are only available when TESTDATA_EDITION=saas");
        }
    }

    private static String bearerToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null) {
            return null;
        }
        String prefix = "bearer ";
        if (header.length() > prefix.length()
                && header.substring(0, prefix.length()).toLowerCase(Locale.ROOT).equals(prefix)) {
            return header.substring(prefix.length()).trim();
        }
        return null;
    }
}
