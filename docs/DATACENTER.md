# Datacenter deployment (IT)

Install **two** Java services and a database. The same two JAR files are used at every site. Site settings stay in files next to the JARs and are not replaced when you upgrade.

| Service | JAR | Default port | What users hit |
|---------|-----|--------------|----------------|
| Studio API | `testdata-generator-api-1.0.0-SNAPSHOT.jar` | 8083 | Not opened in the browser. The UI calls it. |
| Studio UI | `studio-ui-server-1.0.0-SNAPSHOT.jar` | 8080 | The address users bookmark. |

Prerequisite on the host: **Java 17 or Java 21**. Use one JDK for both services. The JARs must be built with Java 17 bytecode (`mvn package -Djava.version=17`). A default Java 21 build does not start on Java 17.

Database: **PostgreSQL** or **MySQL 8**. Create an empty database before the first start. The API creates and updates its own tables (Flyway). Do not create application tables by hand.

Put TLS on the reverse proxy in front of both ports. Do not expose the API or UI directly to the public internet without TLS.

## Layout on the server

Windows example (`D:\testdata`). Linux is the same layout under `/opt/testdata`.

```text
D:\testdata\
  testdata-generator-api-1.0.0-SNAPSHOT.jar
  studio-ui-server-1.0.0-SNAPSHOT.jar
  config\
    api\application.yml
    ui\application.yml
```

The process working directory must be `D:\testdata` so `file:./config/...` resolves. Keep `config\` when you replace a JAR.

## 1. Database

PostgreSQL:

```text
CREATE DATABASE testdata_studio;
```

JDBC URL:

```text
jdbc:postgresql://db-host:5432/testdata_studio?sslmode=require
```

MySQL 8:

```text
CREATE DATABASE testdata_studio CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

JDBC URL:

```text
jdbc:mysql://db-host:3306/testdata_studio?sslMode=REQUIRED&allowPublicKeyRetrieval=true
```

The API account needs permission to create and alter tables in that database.

## 2. API configuration

`config\api\application.yml`:

```yaml
server:
  port: 8083

spring:
  profiles:
    active: datacenter
  datasource:
    url: jdbc:postgresql://db-host:5432/testdata_studio?sslmode=require
    username: testdata
    password: <database-password>

testdata:
  edition: datacenter
  auth:
    provider: local
  saas:
    admin-email: studio-admin@company.com
    admin-password: <at least 8 characters, not a sample>
    cors-origins: https://studio.company.com
    org-name: Company
    workspace-name: Default
```

For MySQL, also set:

```yaml
spring:
  datasource:
    driver-class-name: com.mysql.cj.jdbc.Driver
```

`cors-origins` is the **browser address of the UI** (scheme + host + port if it is not 443). The browser calls the API on a different host, so this must match exactly. Several origins are comma-separated. Do not use `*`.

### Sign-in: local accounts

`testdata.auth.provider: local` stores passwords in the Studio database. `admin-email` and `admin-password` create the first operator on first start. Both are required. Startup fails if either is missing, or if they are still the old samples `saas.admin@local.test` / `admin@local`.

Change the operator password after the first login. Later password changes in the product do not read this file again for an account that already exists.

### Sign-in: Active Directory / LDAP

Use this instead of a local admin password when users sign in with directory accounts:

```yaml
testdata:
  edition: datacenter
  auth:
    provider: ldap
    ldap:
      url: ldaps://ldap.company.com:636
      domain: company.com
      # optional: limit who can sign in
      # allowed-group: CN=Studio Users,OU=Groups,DC=company,DC=com
  saas:
    cors-origins: https://studio.company.com
```

`url` and `domain` are required. Use `ldaps://`. Startup fails if the URL still contains `ldap.client.com`. Users sign in as `user@domain` (`jsmith` is sent as `jsmith@company.com`).

Optional search-then-bind (only if direct bind is not enough): `manager-dn`, `manager-password`, `base-dn`, `user-search-filter`. Optional role mapping from `memberOf`: `group-role.owner`, `group-role.admin`, `group-role.member` (group DNs).

## 3. UI configuration

`config\ui\application.yml`:

```yaml
server:
  port: 8080

studio:
  ui:
    # Public API address. Scheme + host + port. No /api on the end.
    api-origin: https://studio-api.company.com
    security-headers-enabled: true
```

One UI JAR serves every site. Do not rebuild it to change the API address. Restart the UI service after this file changes.

## 4. Start

From `D:\testdata`:

```text
java -jar testdata-generator-api-1.0.0-SNAPSHOT.jar --spring.config.additional-location=file:./config/api/
java -jar studio-ui-server-1.0.0-SNAPSHOT.jar --spring.config.additional-location=file:./config/ui/
```

Start the API first. Register both as services (Windows service or systemd) with that working directory and those arguments. Do not put passwords in the service command line if they are already in `application.yml`. Restrict `config\` to the service account.

If a reverse proxy terminates TLS, leave the JAR ports on localhost and proxy:

| Public name | Forwards to |
|-------------|-------------|
| `https://studio.company.com` | `http://127.0.0.1:8080` |
| `https://studio-api.company.com` | `http://127.0.0.1:8083` |

The proxy must forward `X-Forwarded-Proto` and `Host`. Those names must match `cors-origins` and `studio.ui.api-origin`.

## 5. Checks

API is up and in datacenter mode:

```text
GET https://studio-api.company.com/api/testdata/config
```

Expect HTTP 200 and a JSON body with:

- `edition` = `datacenter`
- `storageType` = `database`
- `loginRequired` = true

UI is serving the API address:

```text
GET https://studio.company.com/studio-ui-config.js
```

`apiBase` must be `https://studio-api.company.com` with no `/api` suffix.

UI health: `GET https://studio.company.com/actuator/health` returns `{"status":"UP"}`.

In a browser, open `https://studio.company.com`, sign in, and open a spec. Refresh `/login`. It must not be a Tomcat 404.

## Startup failures

The API exits before it serves traffic in these cases. Read the first `IllegalStateException` in the log.

| Log text | What to fix |
|----------|-------------|
| refuses hosting profile `local` | `spring.profiles.active` is not `datacenter`, or the external file was not loaded. |
| requires a non-H2 JDBC database | `spring.datasource.url` is missing or is an H2 URL. |
| requires testdata.saas.admin-email and testdata.saas.admin-password | Local sign-in, and the operator account was not set outside the JAR. |
| must not be the sample values | Admin email or password is still the sample. |
| `testdata.auth.ldap.url` is required | `provider: ldap` but URL is empty. |
| sample host `ldap.client.com` | LDAP URL was not replaced. |
| Could not resolve placeholder `SPRING_DATASOURCE_URL` | Datacenter profile is on, and the datasource URL was not set. |

If the home page says it cannot reach the API, but the config URL works from the server, the browser is blocked: `studio.ui.api-origin` or `testdata.saas.cors-origins` does not match the public names.

## Upgrade

1. Stop the UI service, then the API service.
2. Replace the two JAR files. Leave `config\` as it is.
3. Start the API. Wait until Flyway finishes and `/api/testdata/config` returns 200.
4. Start the UI. Check `/studio-ui-config.js` and sign-in.

Take a database backup before the API upgrade. Flyway applies schema changes on startup and does not ask for confirmation.

## What this install does not use

Do not set `TESTDATA_EDITION=saas` or `SPRING_PROFILES_ACTIVE=cloud` for this package. Do not enable the H2 console. Billing and Cloud Run settings are not part of a datacenter install.
