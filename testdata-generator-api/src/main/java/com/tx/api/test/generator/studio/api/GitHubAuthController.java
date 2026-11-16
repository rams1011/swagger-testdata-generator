package com.tx.api.test.generator.studio.api;

import com.tx.api.test.generator.studio.config.StudioProperties;
import com.tx.api.test.generator.studio.tenant.ForbiddenException;
import com.tx.api.test.generator.studio.tenant.github.GitHubAuthService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

@RestController
@RequestMapping("/api/auth/github")
public class GitHubAuthController {

    private static final Logger log = LoggerFactory.getLogger(GitHubAuthController.class);
    static final String UNFINISHED_MESSAGE = "GitHub sign-in didn't finish. Sign in again.";

    private final StudioProperties properties;
    private final GitHubAuthService gitHubAuthService;

    public GitHubAuthController(StudioProperties properties, GitHubAuthService gitHubAuthService) {
        this.properties = properties;
        this.gitHubAuthService = gitHubAuthService;
    }

    @GetMapping("/start")
    public void start(@RequestParam(defaultValue = "login") String intent,
                      @RequestParam(required = false) String returnTo,
                      HttpServletResponse response) throws IOException {
        requireEnabled();
        String url = gitHubAuthService.authorizationUrl(intent, returnTo);
        response.sendRedirect(url);
    }

    @GetMapping("/callback")
    public void callback(@RequestParam(required = false) String code,
                         @RequestParam(required = false) String state,
                         @RequestParam(required = false) String error,
                         @RequestParam(required = false, name = "error_description") String errorDescription,
                         HttpServletResponse response) throws IOException {
        // The browser lands here from GitHub, so every failure goes back to the sign-in page, never a JSON error.
        String redirect;
        try {
            requireEnabled();
            redirect = gitHubAuthService.completeCallback(code, state, error, errorDescription);
        } catch (RuntimeException exception) {
            log.warn("GitHub sign-in callback failed: {}", exception.getMessage());
            redirect = gitHubAuthService.errorRedirect(UNFINISHED_MESSAGE);
        }
        response.sendRedirect(redirect);
    }

    private void requireEnabled() {
        if (!properties.isAuthEnabled()) {
            throw new ForbiddenException("Login is off. Set TESTDATA_EDITION=datacenter or saas.");
        }
        if (!gitHubAuthService.enabled()) {
            throw new ForbiddenException("GitHub sign-in is not enabled");
        }
    }
}
