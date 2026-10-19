# Database configuration

Test Data Studio persists all workspace data through **Spring Boot JDBC** and **Flyway** (`classpath:db/migration`). There is no Excel or custom storage backend.

## Hosting profile → datasource

| Hosting profile | Datasource |
|-----------------|------------|
| `local` | Embedded H2 file (see `application-local.yml`) |
| `docker`, `datacenter`, `cloud` | `SPRING_DATASOURCE_*` (see `application-hosted.yml`) |

Set exactly one hosting profile via `SPRING_PROFILES_ACTIVE`.

## Drivers on the classpath

The API JAR ships with runtime drivers for:

| Engine | Driver | Flyway module |
|--------|--------|---------------|
| H2 (local) | `com.h2database:h2` | built-in |
| PostgreSQL / AlloyDB (PostgreSQL wire) | `org.postgresql:postgresql` | `flyway-database-postgresql` |
| MySQL 8+ | `com.mysql:mysql-connector-j` | `flyway-mysql` |

**MariaDB:** use the MySQL connector and URL form below, or swap in `org.mariadb.jdbc:mariadb-java-client` and set `SPRING_DATASOURCE_DRIVER_CLASS_NAME=org.mariadb.jdbc.Driver`.

**SQL Server / others:** add the JDBC driver to the runtime classpath and set `SPRING_DATASOURCE_DRIVER_CLASS_NAME`. Migrations are written for PostgreSQL/H2/MySQL; SQL Server may need dialect adjustments before production use.

Only **H2, PostgreSQL, and MySQL 8.4** are verified in CI.

## JDBC URL examples

### PostgreSQL (datacenter / Docker)

```bash
SPRING_PROFILES_ACTIVE=docker
SPRING_DATASOURCE_URL=jdbc:postgresql://db:5432/testdata_studio?sslmode=require
SPRING_DATASOURCE_USERNAME=testdata
SPRING_DATASOURCE_PASSWORD=secret
```

### MySQL 8

```bash
SPRING_PROFILES_ACTIVE=datacenter
SPRING_DATASOURCE_URL=jdbc:mysql://db:3306/testdata_studio?sslMode=REQUIRED&allowPublicKeyRetrieval=true
SPRING_DATASOURCE_USERNAME=testdata
SPRING_DATASOURCE_PASSWORD=secret
SPRING_DATASOURCE_DRIVER_CLASS_NAME=com.mysql.cj.jdbc.Driver
```

Create the database once: `CREATE DATABASE testdata_studio CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;`

### Google AlloyDB

Use a **full JDBC URL** in the environment (the application does not rewrite URLs).

**Public IP (typical Cloud Run + VPC egress or public IP enabled):**

```bash
SPRING_PROFILES_ACTIVE=cloud
SPRING_DATASOURCE_URL=jdbc:postgresql://34.x.x.x:5432/testdata_studio?sslmode=require
SPRING_DATASOURCE_USERNAME=postgres
SPRING_DATASOURCE_PASSWORD=secret
```

**AlloyDB Java Connector** (include `alloydb-jdbc-connector` on the classpath — already bundled in the API image):

```bash
SPRING_DATASOURCE_URL=jdbc:postgresql:///testdata_studio?socketFactory=com.google.cloud.alloydb.SocketFactory&alloydbInstanceName=projects/PROJECT/locations/REGION/clusters/CLUSTER/instances/INSTANCE&alloydbIpType=PUBLIC&alloydbRefreshStrategy=lazy
```

The Cloud Run runtime service account needs `roles/alloydb.client` (or equivalent) when using the connector.

**Private IP:** use the instance private address in the host portion, or set `alloydbIpType=PRIVATE` in the connector URL and ensure Cloud Run uses the same VPC/subnet as AlloyDB.

## Migrations

- Location: `testdata-generator-api/src/main/resources/db/migration`
- **PostgreSQL / H2:** `db/migration/` (idempotent upgrade style for existing installs).
- **MySQL 8.4+:** `db/migration_mysql/` (greenfield DDL; selected automatically when `spring.datasource.url` is `jdbc:mysql:` or `jdbc:mariadb:`). Must not live under `db/migration/` — Flyway scans subfolders and would collide on version `1` with PostgreSQL/H2 scripts.
- CI runs Flyway against PostgreSQL and MySQL via Testcontainers.
- Hand-maintained DDL copies: `ddl/schema.sql`, `ddl/postgresql.sql`, `ddl/h2.sql`

**Do not edit** an already-released `V__*.sql` file in place — Flyway stores a checksum in `flyway_schema_history`. Change schema with a new `V5__….sql` migration instead.

### Flyway checksum mismatch (local H2)

If startup fails with `Migration checksum mismatch for migration version 1` after pulling changes that touched an old migration file:

1. **Reset local data** (simplest): stop the API, delete `./studio-output/testdata.mv.db` (and `testdata.trace.db` if present), restart.
2. **Keep data, fix history**: run Flyway repair against the same JDBC URL as `application-local.yml`, e.g. with the Flyway CLI or `mvn -pl testdata-generator-api flyway:repair` if configured.

Production and shared databases should use **repair** only deliberately, not delete files.

## Local H2 overrides

| Variable | Purpose |
|----------|---------|
| `TESTDATA_H2_JDBC_URL` | Full JDBC URL (file or in-memory) |
| `TESTDATA_H2_FILE` | File stem when using default file URL in `application-local.yml` |
| `TESTDATA_H2_CONSOLE` | H2 web console on/off |

In-memory example:

```bash
TESTDATA_H2_JDBC_URL='jdbc:h2:mem:testdata_studio;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE'
```
