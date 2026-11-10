package com.tx.api.test.generator.studio.api;

import com.tx.api.test.generator.studio.config.StudioProperties;
import com.tx.api.test.generator.studio.dto.AuthSessionView;
import com.tx.api.test.generator.studio.dto.OidcExchangeRequest;
import com.tx.api.test.generator.studio.dto.OidcExchangeView;
import com.tx.api.test.generator.studio.dto.OidcRegisterRequest;
import com.tx.api.test.generator.studio.tenant.AuthService;
import com.tx.api.test.generator.studio.tenant.ForbiddenException;
import com.tx.api.test.generator.studio.tenant.oidc.OidcAuthService;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

@RestController
@RequestMapping("/api/auth/oidc")
public class OidcAuthController {

    private final StudioProperties properties;
    private final OidcAuthService oidcAuthService;
    private final AuthService authService;

    public OidcAuthController(StudioProperties properties, OidcAuthService oidcAuthService, AuthService authService) {
        this.properties = properties;
        this.oidcAuthService = oidcAuthService;
        this.authService = authService;
    }

    @GetMapping("/start")
    public void start(@RequestParam(defaultValue = "login") String intent,
                      @RequestParam(required = false) String returnTo,
                      HttpServletResponse response) throws IOException {
        requireOidc();
        String url = oidcAuthService.authorizationUrl(intent, returnTo);
        response.sendRedirect(url);
    }

    @GetMapping("/callback")
    public void callback(@RequestParam(required = false) String code,
                         @RequestParam(required = false) String state,
                         @RequestParam(required = false) String error,
                         @RequestParam(required = false, name = "error_description") String errorDescription,
                         HttpServletResponse response) throws IOException {
        requireOidc();
        String redirect = oidcAuthService.completeCallback(code, state, error, errorDescription);
        response.sendRedirect(redirect);
    }

    @PostMapping("/exchange")
    public OidcExchangeView exchange(@RequestBody OidcExchangeRequest request) {
        requireSocialExchange();
        return authService.exchangeOidc(request);
    }

    @PostMapping("/register")
    public AuthSessionView register(@RequestBody OidcRegisterRequest request) {
        requireSocialExchange();
        requireBilling();
        return authService.registerWithOidc(request);
    }

    private void requireOidc() {
        if (!properties.isAuthEnabled()) {
            throw new ForbiddenException("Login is off. Set TESTDATA_EDITION=datacenter or saas.");
        }
        if (!oidcAuthService.enabled()) {
            throw new ForbiddenException("Google sign-in is not enabled");
        }
    }

    private void requireSocialExchange() {
        if (!properties.isAuthEnabled()) {
            throw new ForbiddenException("Login is off. Set TESTDATA_EDITION=datacenter or saas.");
        }
        if (!properties.isSocialSignInEnabled()) {
            throw new ForbiddenException("Social sign-in is not enabled");
        }
    }

    private void requireBilling() {
        if (!properties.isBillingEnabled()) {
            throw new ForbiddenException("Plans and self-service signup are only available when TESTDATA_EDITION=saas");
        }
    }
}
