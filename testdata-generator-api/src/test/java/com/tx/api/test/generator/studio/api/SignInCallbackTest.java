package com.tx.api.test.generator.studio.api;

import com.tx.api.test.generator.studio.TestSchema;
import com.tx.api.test.generator.studio.config.H2Database;
import com.tx.api.test.generator.studio.config.StudioProperties;
import com.tx.api.test.generator.studio.tenant.JdbcTenantStore;
import com.tx.api.test.generator.studio.tenant.github.GitHubAuthService;
import com.tx.api.test.generator.studio.tenant.oidc.OidcAuthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletResponse;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The browser lands on the sign-in callbacks, so a failure there must go back to the UI, never show JSON. */
class SignInCallbackTest {

    private static final String UI = "http://localhost:5173";
    private static final String ERROR_PAGE = UI + "/login/oidc?error=";

    private StudioProperties properties;
    private JdbcTenantStore tenantStore;

    @BeforeEach
    void setUp() throws Exception {
        DriverManagerDataSource h2 = new DriverManagerDataSource(
                H2Database.memoryJdbcUrl("sign_in_callback_" + System.nanoTime()), "sa", "");
        h2.setDriverClassName(H2Database.DRIVER);
        TestSchema.migrate(h2);
        tenantStore = new JdbcTenantStore(new JdbcTemplate(h2));
        properties = new StudioProperties();
        properties.setEdition("saas");
        StudioProperties.Oidc oidc = properties.getAuth().getOidc();
        oidc.setEnabled(true);
        oidc.setIssuer("https://accounts.google.com");
        oidc.setClientId("client");
        oidc.setClientSecret("secret");
        oidc.setRedirectUri("http://localhost:8083/api/auth/oidc/callback");
        oidc.setUiBase(UI + "/");
        StudioProperties.Github github = properties.getAuth().getGithub();
        github.setEnabled(true);
        github.setClientId("client");
        github.setClientSecret("secret");
        github.setRedirectUri("http://localhost:8083/api/auth/github/callback");
        github.setUiBase(UI);
    }

    @Test
    void anExpiredGoogleSignInGoesBackToTheSignInPage() throws Exception {
        OidcAuthController controller = new OidcAuthController(
                properties, new OidcAuthService(properties, tenantStore), null);
        MockHttpServletResponse response = new MockHttpServletResponse();
        controller.callback("code", "no-such-state", null, null, response);
        assertEquals(OidcAuthService.EXPIRED_MESSAGE, errorShown(response));
    }

    @Test
    void anExpiredGitHubSignInGoesBackToTheSignInPage() throws Exception {
        GitHubAuthController controller = new GitHubAuthController(
                properties, new GitHubAuthService(properties, tenantStore));
        MockHttpServletResponse response = new MockHttpServletResponse();
        controller.callback("code", "no-such-state", null, null, response);
        assertEquals(OidcAuthService.EXPIRED_MESSAGE, errorShown(response));
    }

    @Test
    void anyOtherCallbackFailureAlsoGoesBackToTheSignInPage() throws Exception {
        properties.getAuth().getOidc().setClientSecret("");
        OidcAuthController google = new OidcAuthController(
                properties, new OidcAuthService(properties, tenantStore), null);
        MockHttpServletResponse response = new MockHttpServletResponse();
        google.callback("code", "state", null, null, response);
        assertEquals(OidcAuthController.UNFINISHED_MESSAGE, errorShown(response));

        properties.getAuth().getGithub().setEnabled(false);
        GitHubAuthController gitHub = new GitHubAuthController(
                properties, new GitHubAuthService(properties, tenantStore));
        MockHttpServletResponse gitHubResponse = new MockHttpServletResponse();
        gitHub.callback("code", "state", null, null, gitHubResponse);
        assertEquals(GitHubAuthController.UNFINISHED_MESSAGE, errorShown(gitHubResponse));
    }

    @Test
    void aProviderErrorKeepsItsMessage() throws Exception {
        OidcAuthController controller = new OidcAuthController(
                properties, new OidcAuthService(properties, tenantStore), null);
        MockHttpServletResponse response = new MockHttpServletResponse();
        controller.callback(null, null, "access_denied", "You cancelled sign-in", response);
        assertEquals("You cancelled sign-in", errorShown(response));
    }

    private static String errorShown(MockHttpServletResponse response) {
        String location = response.getRedirectedUrl();
        assertNotNull(location, "the callback redirects instead of writing a body");
        assertEquals(302, response.getStatus());
        assertTrue(location.startsWith(ERROR_PAGE), location);
        return URLDecoder.decode(location.substring(ERROR_PAGE.length()), StandardCharsets.UTF_8);
    }
}
