package com.tx.api.test.generator.studio.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "testdata")
public class StudioProperties {

    private Storage storage = new Storage();
    private Excel excel = new Excel();
    private H2 h2 = new H2();
    private String defaultOutputDir = "D:/code/api-automation/src/test/data";
    private String defaultExcelFileName = "TestData.xlsx";
    private String payloadPathPrefix = "src/test/data/payloads";
    private String schemaPathPrefix = "src/test/data/schemas";
    private String filesPathPrefix = "src/test/data/files";
    private String downloadsPathPrefix = "src/test/data/downloads";

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

    public String getPayloadPathPrefix() {
        return payloadPathPrefix;
    }

    public void setPayloadPathPrefix(String payloadPathPrefix) {
        this.payloadPathPrefix = payloadPathPrefix;
    }

    public String getSchemaPathPrefix() {
        return schemaPathPrefix;
    }

    public void setSchemaPathPrefix(String schemaPathPrefix) {
        this.schemaPathPrefix = schemaPathPrefix;
    }

    public String getFilesPathPrefix() {
        return filesPathPrefix;
    }

    public void setFilesPathPrefix(String filesPathPrefix) {
        this.filesPathPrefix = filesPathPrefix;
    }

    public String getDownloadsPathPrefix() {
        return downloadsPathPrefix;
    }

    public void setDownloadsPathPrefix(String downloadsPathPrefix) {
        this.downloadsPathPrefix = downloadsPathPrefix;
    }

    public boolean isH2Storage() {
        return storage != null && storage.isH2();
    }

    public boolean isDatabaseStorage() {
        return storage != null && storage.isJdbc();
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
        private boolean console = true;
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
}
