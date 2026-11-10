package com.tx.api.test.generator.studio.config;

import com.tx.api.test.generator.studio.tenant.LoginGuard;
import com.tx.api.test.generator.studio.tenant.PasswordPolicy;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "testdata", ignoreUnknownFields = true)
public class StudioProperties {

    private H2 h2 = new H2();
    private RunHistory runHistory = new RunHistory();
    private Run run = new Run();
    private Saas saas = new Saas();
    private Auth auth = new Auth();
    /**
     * Hosting mode. The only switch operators should set:
     * {@code local} (no login), {@code datacenter} (login, no plan), {@code saas} (login + plan).
     * Blank defaults to {@code datacenter}.
     */
    private String edition = "";
    private String defaultOutputDir = "./studio-output";
    private Cache cache = new Cache();

    public H2 getH2() {
        return h2;
    }

    public void setH2(H2 h2) {
        this.h2 = h2;
    }

    public RunHistory getRunHistory() {
        return runHistory;
    }

    public void setRunHistory(RunHistory runHistory) {
        this.runHistory = runHistory;
    }

    public Run getRun() {
        return run;
    }

    public void setRun(Run run) {
        this.run = run == null ? new Run() : run;
    }

    public Saas getSaas() {
        return saas;
    }

    public void setSaas(Saas saas) {
        this.saas = saas;
    }

    public Auth getAuth() {
        return auth;
    }

    public void setAuth(Auth auth) {
        this.auth = auth == null ? new Auth() : auth;
    }

    /**
     * Spring maps {@code TESTDATA_OIDC_*} to flat {@code testdata.oidc-*} — deprecated; use {@code TESTDATA_AUTH_OIDC_*}.
     */
    public void setOidcEnabled(boolean enabled) {
        auth.getOidc().setEnabled(enabled);
    }

    public void setOidcIssuer(String issuer) {
        if (issuer != null) {
            auth.getOidc().setIssuer(issuer);
        }
    }

    public void setOidcClientId(String clientId) {
        if (clientId != null) {
            auth.getOidc().setClientId(clientId);
        }
    }

    public void setOidcClientSecret(String clientSecret) {
        if (clientSecret != null) {
            auth.getOidc().setClientSecret(clientSecret);
        }
    }

    public void setOidcRedirectUri(String redirectUri) {
        if (redirectUri != null) {
            auth.getOidc().setRedirectUri(redirectUri);
        }
    }

    public void setOidcUiBase(String uiBase) {
        if (uiBase != null) {
            auth.getOidc().setUiBase(uiBase);
        }
    }

    public void setOidcAllowRegistration(boolean allowRegistration) {
        auth.getOidc().setAllowRegistration(allowRegistration);
    }

    public boolean isLdapAuth() {
        return isAuthEnabled() && getAuth().isLdap();
    }

    public String resolvedAuthProvider() {
        if (!isAuthEnabled()) {
            return "none";
        }
        return getAuth().resolvedProvider().wireValue();
    }

    public String getEdition() {
        return edition;
    }

    public void setEdition(String edition) {
        this.edition = edition;
    }

    public StudioEdition resolvedEdition() {
        StudioEdition parsed = StudioEdition.from(edition);
        if (parsed != null) {
            return parsed;
        }
        return StudioEdition.DATACENTER;
    }

    public String editionName() {
        return resolvedEdition().wireValue();
    }

    public boolean isAuthEnabled() {
        return resolvedEdition() != StudioEdition.LOCAL;
    }

    public boolean isBillingEnabled() {
        return resolvedEdition() == StudioEdition.SAAS;
    }

    public boolean isDatacenter() {
        return resolvedEdition() == StudioEdition.DATACENTER;
    }

    public boolean isSaasEnabled() {
        return saas != null && saas.isEnabled();
    }

    public void setSaasEnabled(boolean enabled) {
        if (saas == null) {
            saas = new Saas();
        }
        saas.setEnabled(enabled);
    }

    public String getDefaultOutputDir() {
        return defaultOutputDir;
    }

    public void setDefaultOutputDir(String defaultOutputDir) {
        this.defaultOutputDir = defaultOutputDir;
    }

    public static class H2 {
        private String file = H2Database.DEFAULT_FILE;
        private String username = H2Database.DEFAULT_USERNAME;
        private String password = "";
        private boolean console = false;
        private boolean inMemory = false;

        public String getFile() {
            return file;
        }

        public void setFile(String file) {
            this.file = file;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public boolean isConsole() {
            return console;
        }

        public void setConsole(boolean console) {
            this.console = console;
        }

        public boolean isInMemory() {
            return inMemory;
        }

        public void setInMemory(boolean inMemory) {
            this.inMemory = inMemory;
        }

        public String jdbcUrl() {
            return H2Database.jdbcUrl(file, inMemory);
        }
    }

    public static class RunHistory {
        /**
         * Oldest runs for a spec are deleted after a new write exceeds this count.
         * {@code 0} disables pruning.
         */
        private int maxPerSpec = 100;

        public int getMaxPerSpec() {
            return maxPerSpec;
        }

        public void setMaxPerSpec(int maxPerSpec) {
            this.maxPerSpec = maxPerSpec;
        }

        public int resolvedMaxPerSpec() {
            if (maxPerSpec < 0) {
                return 100;
            }
            return maxPerSpec;
        }
    }

    /**
     * Limits where Try and Run plan may send the API process.
     */
    public static class Run {
        /**
         * When false, loopback, private, link-local, and cloud-metadata targets are rejected.
         * The local profile turns this on so a desktop app can call localhost.
         * Deprecated fallback: prefer {@link #privateTargetAllowlist}. The always-blocked targets stay blocked.
         */
        private boolean allowPrivateNetworks = false;
        /**
         * Private targets an environment may call, by environment id (not name, which any user can change).
         * Entries are {@code host:port}, {@code ip:port}, {@code cidr:port}, or {@code [ipv6-or-cidr]:port}.
         */
        private java.util.Map<String, java.util.List<String>> privateTargetAllowlist = new java.util.LinkedHashMap<>();
        /** Comma-separated ports that are always blocked on non-public addresses, whatever the allowlist says. */
        private String blockedInfrastructurePorts = "22,25,1433,2375,2376,2379,2380,3306,5432,6379,6443,10250,10255,11211,27017";
        /**
         * Comma-separated hosts. {@code *.example.com} matches subdomains only.
         * Empty means any host that passes the network check.
         */
        private String allowHosts = "";
        /** Upper bound for a request's timeout, including an environment's request timeout setting. */
        private long maxRequestTimeoutMs = 300_000L;
        /** Response bodies longer than this are cut in run results and history (checks still see the full body). */
        private int maxResponseChars = 1_000_000;

        public long getMaxRequestTimeoutMs() {
            return maxRequestTimeoutMs;
        }

        public void setMaxRequestTimeoutMs(long maxRequestTimeoutMs) {
            this.maxRequestTimeoutMs = maxRequestTimeoutMs;
        }

        public int getMaxResponseChars() {
            return maxResponseChars;
        }

        public void setMaxResponseChars(int maxResponseChars) {
            this.maxResponseChars = maxResponseChars;
        }

        public java.util.Map<String, java.util.List<String>> getPrivateTargetAllowlist() {
            return privateTargetAllowlist;
        }

        public void setPrivateTargetAllowlist(java.util.Map<String, java.util.List<String>> privateTargetAllowlist) {
            this.privateTargetAllowlist = privateTargetAllowlist == null
                    ? new java.util.LinkedHashMap<>() : privateTargetAllowlist;
        }

        public String getBlockedInfrastructurePorts() {
            return blockedInfrastructurePorts;
        }

        public void setBlockedInfrastructurePorts(String blockedInfrastructurePorts) {
            this.blockedInfrastructurePorts = blockedInfrastructurePorts;
        }

        public java.util.Set<Integer> blockedInfrastructurePortSet() {
            if (blockedInfrastructurePorts == null || blockedInfrastructurePorts.isBlank()) {
                return java.util.Set.of();
            }
            return java.util.Arrays.stream(blockedInfrastructurePorts.split(","))
                    .map(String::trim)
                    .filter(value -> !value.isEmpty())
                    .map(Integer::valueOf)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
        }

        /** @deprecated Use {@link #getPrivateTargetAllowlist()}. */
        @Deprecated
        public boolean isAllowPrivateNetworks() {
            return allowPrivateNetworks;
        }

        /** @deprecated Use {@link #setPrivateTargetAllowlist(java.util.Map)}. */
        @Deprecated
        public void setAllowPrivateNetworks(boolean allowPrivateNetworks) {
            this.allowPrivateNetworks = allowPrivateNetworks;
        }

        public String getAllowHosts() {
            return allowHosts;
        }

        public void setAllowHosts(String allowHosts) {
            this.allowHosts = allowHosts;
        }

        public java.util.List<String> allowHostRules() {
            if (allowHosts == null || allowHosts.isBlank()) {
                return java.util.List.of();
            }
            return java.util.Arrays.stream(allowHosts.split(","))
                    .map(String::trim)
                    .filter(value -> !value.isEmpty())
                    .toList();
        }
    }

    public static class Saas {
        /**
         * Deprecated. Hosting mode is {@code testdata.edition} only.
         */
        private boolean enabled = true;
        private int sessionDays = 14;
        private String corsOrigins = "http://localhost:[*],http://127.0.0.1:[*]";
        private String adminEmail = "";
        private String adminPassword = "";
        private String orgName = "Studio";
        private String workspaceName = "Default";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getSessionDays() {
            return sessionDays;
        }

        public void setSessionDays(int sessionDays) {
            this.sessionDays = sessionDays;
        }

        public int resolvedSessionDays() {
            return sessionDays < 1 ? 14 : sessionDays;
        }

        public String getCorsOrigins() {
            return corsOrigins;
        }

        public void setCorsOrigins(String corsOrigins) {
            this.corsOrigins = corsOrigins;
        }

        /**
         * Patterns passed to Spring {@code allowedOriginPatterns}.
         * {@code http://localhost:*} is rewritten to {@code http://localhost:[*]} so any port matches,
         * including the default port. {@code localhost} and {@code 127.0.0.1} are both allowed when either is set.
         */
        public String[] originPatterns() {
            String raw = corsOrigins == null || corsOrigins.isBlank()
                    ? "http://localhost:[*],http://127.0.0.1:[*]"
                    : corsOrigins;
            java.util.LinkedHashSet<String> patterns = new java.util.LinkedHashSet<>();
            for (String part : raw.split(",")) {
                String value = normalizeOriginPattern(part.trim());
                if (value.isEmpty()) {
                    continue;
                }
                patterns.add(value);
                String loopback = loopbackAlias(value);
                if (loopback != null) {
                    patterns.add(loopback);
                }
            }
            return patterns.toArray(String[]::new);
        }

        /**
         * Spring's any-port syntax is {@code :[*]}, which also matches a host with no port.
         * A trailing {@code :*} matches only when the origin includes a port.
         */
        static String normalizeOriginPattern(String pattern) {
            if (pattern.endsWith(":*") && !pattern.endsWith(":[*]")) {
                return pattern.substring(0, pattern.length() - 2) + ":[*]";
            }
            return pattern;
        }

        /** Pair {@code localhost} with {@code 127.0.0.1} when the host is exactly one of those names. */
        static String loopbackAlias(String pattern) {
            int scheme = pattern.indexOf("://");
            if (scheme < 0) {
                return null;
            }
            int hostStart = scheme + 3;
            String rest = pattern.substring(hostStart);
            String host;
            String suffix;
            if (rest.startsWith("localhost")) {
                host = "localhost";
                suffix = rest.substring("localhost".length());
            } else if (rest.startsWith("127.0.0.1")) {
                host = "127.0.0.1";
                suffix = rest.substring("127.0.0.1".length());
            } else {
                return null;
            }
            if (!suffix.isEmpty() && !suffix.startsWith(":")) {
                return null;
            }
            String other = "localhost".equals(host) ? "127.0.0.1" : "localhost";
            return pattern.substring(0, hostStart) + other + suffix;
        }

        public String getAdminEmail() {
            return adminEmail;
        }

        public void setAdminEmail(String adminEmail) {
            this.adminEmail = adminEmail;
        }

        public String getAdminPassword() {
            return adminPassword;
        }

        public void setAdminPassword(String adminPassword) {
            this.adminPassword = adminPassword;
        }

        public String normalizedAdminEmail() {
            if (adminEmail == null || adminEmail.isBlank() || !adminEmail.contains("@")) {
                return null;
            }
            return adminEmail.trim().toLowerCase(java.util.Locale.ROOT);
        }

        public String getOrgName() {
            return orgName;
        }

        public void setOrgName(String orgName) {
            this.orgName = orgName;
        }

        public String resolvedOrgName() {
            if (orgName == null || orgName.isBlank()) {
                return "Studio";
            }
            return orgName.trim();
        }

        public String getWorkspaceName() {
            return workspaceName;
        }

        public void setWorkspaceName(String workspaceName) {
            this.workspaceName = workspaceName;
        }

        public String resolvedWorkspaceName() {
            if (workspaceName == null || workspaceName.isBlank()) {
                return "Default";
            }
            return workspaceName.trim();
        }
    }

    public static class Auth {
        private String provider = "local";
        private boolean localAdminFallback = true;
        private int passwordMaxAgeDays = PasswordPolicy.DEFAULT_MAX_AGE_DAYS;
        private int passwordHistory = PasswordPolicy.DEFAULT_HISTORY;
        private int loginMaxFailures = LoginGuard.DEFAULT_MAX_FAILURES;
        private int loginLockoutMinutes = LoginGuard.DEFAULT_LOCKOUT_MINUTES;
        private int loginIpMaxAttempts = LoginGuard.DEFAULT_IP_MAX_ATTEMPTS;
        private int loginIpWindowSeconds = LoginGuard.DEFAULT_IP_WINDOW_SECONDS;
        private int sessionIdleMinutes = LoginGuard.DEFAULT_IDLE_MINUTES;
        private Ldap ldap = new Ldap();
        private Oidc oidc = new Oidc();
        private Github github = new Github();

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }

        public int getPasswordMaxAgeDays() {
            return passwordMaxAgeDays;
        }

        public void setPasswordMaxAgeDays(int passwordMaxAgeDays) {
            this.passwordMaxAgeDays = passwordMaxAgeDays;
        }

        public int resolvedPasswordMaxAgeDays() {
            return Math.max(passwordMaxAgeDays, 0);
        }

        public int getPasswordHistory() {
            return passwordHistory;
        }

        public void setPasswordHistory(int passwordHistory) {
            this.passwordHistory = passwordHistory;
        }

        public int resolvedPasswordHistory() {
            return passwordHistory < 1 ? 1 : passwordHistory;
        }

        public int getLoginMaxFailures() {
            return loginMaxFailures;
        }

        public void setLoginMaxFailures(int loginMaxFailures) {
            this.loginMaxFailures = loginMaxFailures;
        }

        public int resolvedLoginMaxFailures() {
            return Math.max(loginMaxFailures, 0);
        }

        public int getLoginLockoutMinutes() {
            return loginLockoutMinutes;
        }

        public void setLoginLockoutMinutes(int loginLockoutMinutes) {
            this.loginLockoutMinutes = loginLockoutMinutes;
        }

        public int resolvedLoginLockoutMinutes() {
            return Math.max(loginLockoutMinutes, 0);
        }

        public int getLoginIpMaxAttempts() {
            return loginIpMaxAttempts;
        }

        public void setLoginIpMaxAttempts(int loginIpMaxAttempts) {
            this.loginIpMaxAttempts = loginIpMaxAttempts;
        }

        public int resolvedLoginIpMaxAttempts() {
            return Math.max(loginIpMaxAttempts, 0);
        }

        public int getLoginIpWindowSeconds() {
            return loginIpWindowSeconds;
        }

        public void setLoginIpWindowSeconds(int loginIpWindowSeconds) {
            this.loginIpWindowSeconds = loginIpWindowSeconds;
        }

        public int resolvedLoginIpWindowSeconds() {
            return Math.max(loginIpWindowSeconds, 0);
        }

        public int getSessionIdleMinutes() {
            return sessionIdleMinutes;
        }

        public void setSessionIdleMinutes(int sessionIdleMinutes) {
            this.sessionIdleMinutes = sessionIdleMinutes;
        }

        public int resolvedSessionIdleMinutes() {
            return Math.max(sessionIdleMinutes, 0);
        }

        public boolean isLocalAdminFallback() {
            return localAdminFallback;
        }

        public void setLocalAdminFallback(boolean localAdminFallback) {
            this.localAdminFallback = localAdminFallback;
        }

        public Ldap getLdap() {
            return ldap;
        }

        public void setLdap(Ldap ldap) {
            this.ldap = ldap == null ? new Ldap() : ldap;
        }

        public boolean isLdap() {
            return resolvedProvider() == AuthProvider.LDAP;
        }

        public AuthProvider resolvedProvider() {
            if (org.apache.commons.lang3.StringUtils.isBlank(provider)) {
                return AuthProvider.LOCAL;
            }
            return AuthProvider.from(provider);
        }

        public Oidc getOidc() {
            return oidc;
        }

        public void setOidc(Oidc oidc) {
            this.oidc = oidc == null ? new Oidc() : oidc;
        }

        public boolean isOidcConfigured() {
            return oidc != null && oidc.isConfigured();
        }

        public Github getGithub() {
            return github;
        }

        public void setGithub(Github github) {
            this.github = github == null ? new Github() : github;
        }

        public boolean isGithubConfigured() {
            return github != null && github.isConfigured();
        }
    }

    public boolean isOidcEnabled() {
        return isAuthEnabled() && getAuth().isOidcConfigured();
    }

    public boolean isGithubEnabled() {
        return isAuthEnabled() && getAuth().isGithubConfigured();
    }

    public boolean isSocialSignInEnabled() {
        return isOidcEnabled() || isGithubEnabled();
    }

    public static class Oidc {
        private boolean enabled = false;
        private String issuer = "https://accounts.google.com";
        private String clientId = "";
        private String clientSecret = "";
        private String redirectUri = "http://localhost:8083/api/auth/oidc/callback";
        private String uiBase = "http://localhost:5173";
        private boolean allowRegistration = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getIssuer() {
            return issuer;
        }

        public void setIssuer(String issuer) {
            this.issuer = issuer;
        }

        public String resolvedIssuer() {
            return org.apache.commons.lang3.StringUtils.trimToEmpty(issuer);
        }

        public String getClientId() {
            return clientId;
        }

        public void setClientId(String clientId) {
            this.clientId = clientId;
        }

        public String getClientSecret() {
            return clientSecret;
        }

        public void setClientSecret(String clientSecret) {
            this.clientSecret = clientSecret;
        }

        public String getRedirectUri() {
            return redirectUri;
        }

        public void setRedirectUri(String redirectUri) {
            this.redirectUri = redirectUri;
        }

        public String resolvedRedirectUri() {
            return org.apache.commons.lang3.StringUtils.trimToEmpty(redirectUri);
        }

        public String getUiBase() {
            return uiBase;
        }

        public void setUiBase(String uiBase) {
            this.uiBase = uiBase;
        }

        public String resolvedUiBase() {
            String base = org.apache.commons.lang3.StringUtils.trimToEmpty(uiBase);
            if (base.endsWith("/")) {
                return base.substring(0, base.length() - 1);
            }
            return base;
        }

        public boolean isAllowRegistration() {
            return allowRegistration;
        }

        public void setAllowRegistration(boolean allowRegistration) {
            this.allowRegistration = allowRegistration;
        }

        public boolean isConfigured() {
            return enabled
                    && org.apache.commons.lang3.StringUtils.isNotBlank(clientId)
                    && org.apache.commons.lang3.StringUtils.isNotBlank(clientSecret)
                    && org.apache.commons.lang3.StringUtils.isNotBlank(resolvedRedirectUri())
                    && org.apache.commons.lang3.StringUtils.isNotBlank(resolvedIssuer())
                    && org.apache.commons.lang3.StringUtils.isNotBlank(resolvedUiBase());
        }
    }

    public static class Github {
        private boolean enabled = false;
        private String clientId = "";
        private String clientSecret = "";
        private String redirectUri = "http://localhost:8083/api/auth/github/callback";
        private String uiBase = "http://localhost:5173";
        private boolean allowRegistration = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getClientId() {
            return clientId;
        }

        public void setClientId(String clientId) {
            this.clientId = clientId;
        }

        public String getClientSecret() {
            return clientSecret;
        }

        public void setClientSecret(String clientSecret) {
            this.clientSecret = clientSecret;
        }

        public String getRedirectUri() {
            return redirectUri;
        }

        public void setRedirectUri(String redirectUri) {
            this.redirectUri = redirectUri;
        }

        public String resolvedRedirectUri() {
            return org.apache.commons.lang3.StringUtils.trimToEmpty(redirectUri);
        }

        public String getUiBase() {
            return uiBase;
        }

        public void setUiBase(String uiBase) {
            this.uiBase = uiBase;
        }

        public String resolvedUiBase() {
            String base = org.apache.commons.lang3.StringUtils.trimToEmpty(uiBase);
            if (base.endsWith("/")) {
                return base.substring(0, base.length() - 1);
            }
            return base;
        }

        public boolean isAllowRegistration() {
            return allowRegistration;
        }

        public void setAllowRegistration(boolean allowRegistration) {
            this.allowRegistration = allowRegistration;
        }

        public boolean isConfigured() {
            return enabled
                    && org.apache.commons.lang3.StringUtils.isNotBlank(clientId)
                    && org.apache.commons.lang3.StringUtils.isNotBlank(clientSecret)
                    && org.apache.commons.lang3.StringUtils.isNotBlank(resolvedRedirectUri())
                    && org.apache.commons.lang3.StringUtils.isNotBlank(resolvedUiBase());
        }
    }

    public static class Ldap {
        private String url = "";
        private String domain = "";
        private boolean allowInsecure = false;
        private int connectTimeoutMs = 5_000;
        private int readTimeoutMs = 5_000;
        private String managerDn = "";
        private String managerPassword = "";
        private String baseDn = "";
        private String userSearchBase = "";
        private String userSearchFilter = "";
        private String allowedGroup = "";
        private String emailAttribute = "mail";
        private String displayNameAttribute = "displayName";
        private GroupRole groupRole = new GroupRole();

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getDomain() {
            return domain;
        }

        public void setDomain(String domain) {
            this.domain = domain;
        }

        public boolean isAllowInsecure() {
            return allowInsecure;
        }

        public void setAllowInsecure(boolean allowInsecure) {
            this.allowInsecure = allowInsecure;
        }

        public int getConnectTimeoutMs() {
            return connectTimeoutMs;
        }

        public void setConnectTimeoutMs(int connectTimeoutMs) {
            this.connectTimeoutMs = connectTimeoutMs;
        }

        public int getReadTimeoutMs() {
            return readTimeoutMs;
        }

        public void setReadTimeoutMs(int readTimeoutMs) {
            this.readTimeoutMs = readTimeoutMs;
        }

        public int resolvedConnectTimeoutMs() {
            return connectTimeoutMs < 1 ? 5_000 : connectTimeoutMs;
        }

        public int resolvedReadTimeoutMs() {
            return readTimeoutMs < 1 ? 5_000 : readTimeoutMs;
        }

        public String getManagerDn() {
            return managerDn;
        }

        public void setManagerDn(String managerDn) {
            this.managerDn = managerDn;
        }

        public String getManagerPassword() {
            return managerPassword;
        }

        public void setManagerPassword(String managerPassword) {
            this.managerPassword = managerPassword;
        }

        public boolean hasManagerCredentials() {
            return org.apache.commons.lang3.StringUtils.isNotBlank(managerDn);
        }

        public String getBaseDn() {
            return baseDn;
        }

        public void setBaseDn(String baseDn) {
            this.baseDn = baseDn;
        }

        public String resolvedBaseDn() {
            if (org.apache.commons.lang3.StringUtils.isNotBlank(baseDn)) {
                return baseDn.trim();
            }
            return com.tx.api.test.generator.studio.tenant.LdapLoginNames.domainToBaseDn(normalizedDomain());
        }

        public String normalizedDomain() {
            if (domain == null || domain.isBlank()) {
                return null;
            }
            return domain.trim().toLowerCase(java.util.Locale.ROOT);
        }

        public String getUserSearchBase() {
            return userSearchBase;
        }

        public void setUserSearchBase(String userSearchBase) {
            this.userSearchBase = userSearchBase;
        }

        public String getUserSearchFilter() {
            return userSearchFilter;
        }

        public void setUserSearchFilter(String userSearchFilter) {
            this.userSearchFilter = userSearchFilter;
        }

        public String getAllowedGroup() {
            return allowedGroup;
        }

        public void setAllowedGroup(String allowedGroup) {
            this.allowedGroup = allowedGroup;
        }

        public String getEmailAttribute() {
            return emailAttribute;
        }

        public void setEmailAttribute(String emailAttribute) {
            this.emailAttribute = emailAttribute;
        }

        public String getDisplayNameAttribute() {
            return displayNameAttribute;
        }

        public void setDisplayNameAttribute(String displayNameAttribute) {
            this.displayNameAttribute = displayNameAttribute;
        }

        public GroupRole getGroupRole() {
            return groupRole;
        }

        public void setGroupRole(GroupRole groupRole) {
            this.groupRole = groupRole == null ? new GroupRole() : groupRole;
        }

        public boolean hasGroupRoleMapping() {
            return org.apache.commons.lang3.StringUtils.isNotBlank(groupRole.getOwner())
                    || org.apache.commons.lang3.StringUtils.isNotBlank(groupRole.getAdmin())
                    || org.apache.commons.lang3.StringUtils.isNotBlank(groupRole.getMember());
        }
    }

    public Cache getCache() {
        return cache;
    }

    public void setCache(Cache cache) {
        this.cache = cache == null ? new Cache() : cache;
    }

    public static class Cache {
        private Redis redis = new Redis();

        public Redis getRedis() {
            return redis;
        }

        public void setRedis(Redis redis) {
            this.redis = redis == null ? new Redis() : redis;
        }
    }

    public static class Redis {
        private boolean enabled;
        private String host = "localhost";
        private int port = 6379;
        private String password = "";
        private boolean ssl;
        private boolean disablePeerVerification = true;
        private long ttlSeconds = 300;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host;
        }

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public boolean isSsl() {
            return ssl;
        }

        public void setSsl(boolean ssl) {
            this.ssl = ssl;
        }

        public boolean isDisablePeerVerification() {
            return disablePeerVerification;
        }

        public void setDisablePeerVerification(boolean disablePeerVerification) {
            this.disablePeerVerification = disablePeerVerification;
        }

        public long getTtlSeconds() {
            return ttlSeconds;
        }

        public void setTtlSeconds(long ttlSeconds) {
            this.ttlSeconds = ttlSeconds;
        }
    }

    public static class GroupRole {
        private String owner = "";
        private String admin = "";
        private String member = "";

        public String getOwner() {
            return owner;
        }

        public void setOwner(String owner) {
            this.owner = owner;
        }

        public String getAdmin() {
            return admin;
        }

        public void setAdmin(String admin) {
            this.admin = admin;
        }

        public String getMember() {
            return member;
        }

        public void setMember(String member) {
            this.member = member;
        }
    }
}
