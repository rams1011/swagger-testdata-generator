package com.tx.api.test.generator.studio.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "testdata")
public class StudioProperties {

    private Storage storage = new Storage();
    private Excel excel = new Excel();
    private H2 h2 = new H2();
    private RunHistory runHistory = new RunHistory();
    private Saas saas = new Saas();
    /**
     * Hosting mode. The only switch operators should set:
     * {@code local} (no login), {@code datacenter} (login, no plan), {@code saas} (login + plan).
     * Blank defaults to {@code datacenter}.
     */
    private String edition = "";
    private String defaultOutputDir = "./studio-output";
    private String defaultExcelFileName = "TestData.xlsx";

    public Storage getStorage() {
        return storage;
    }

    public void setStorage(Storage storage) {
        this.storage = storage;
    }

    public Excel getExcel() {
        return excel;
    }

    public void setExcel(Excel excel) {
        this.excel = excel;
    }

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

    public Saas getSaas() {
        return saas;
    }

    public void setSaas(Saas saas) {
        this.saas = saas;
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

    public String getDefaultExcelFileName() {
        return defaultExcelFileName;
    }

    public void setDefaultExcelFileName(String defaultExcelFileName) {
        this.defaultExcelFileName = defaultExcelFileName;
    }

    public boolean isH2Storage() {
        return storage != null && storage.isH2();
    }

    public static class Storage {
        /**
         * excel, h2, or database (PostgreSQL)
         */
        private String type = "excel";

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public boolean isH2() {
            return type != null && "h2".equalsIgnoreCase(type.trim());
        }

        public boolean isDatabase() {
            return type != null && "database".equalsIgnoreCase(type.trim());
        }

        public boolean isJdbc() {
            return isH2() || isDatabase();
        }
    }

    public static class Excel {
        private String file = "./studio-output/studio-testdata.xlsx";

        public String getFile() {
            return file;
        }

        public void setFile(String file) {
            this.file = file;
        }
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

    public static class Saas {
        /**
         * Deprecated. Hosting mode is {@code testdata.edition} only.
         */
        private boolean enabled = true;
        private int sessionDays = 14;
        private String corsOrigins = "http://localhost:*,http://127.0.0.1:*";
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

        public String[] originPatterns() {
            if (corsOrigins == null || corsOrigins.isBlank()) {
                return new String[] {
                        "http://localhost:*",
                        "http://127.0.0.1:*"
                };
            }
            return java.util.Arrays.stream(corsOrigins.split(","))
                    .map(String::trim)
                    .filter(value -> !value.isBlank())
                    .toArray(String[]::new);
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
}
