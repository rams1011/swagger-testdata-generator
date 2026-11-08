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

The migrations are the only definition of the schema. There are no separate DDL files, and the tests build their databases with the same migrations.

- **PostgreSQL / H2:** `testdata-generator-api/src/main/resources/db/migration/`.
- **MySQL 8.4+:** `db/migration_mysql/`, selected automatically when `spring.datasource.url` is `jdbc:mysql:` or `jdbc:mariadb:`. Same tables, columns, keys and indexes, with MySQL types: `LONGTEXT` (MySQL `TEXT` stops at 64 KB), `DATETIME(3)` and `LONGBLOB`. It must not live under `db/migration/`, or Flyway would see each version twice.
- **`V1__studio.sql`** in each folder is the baseline, written fresh on 2026-11-07 in place of the V1 to V16 chain from development. **`R__default_workspaces.sql`** seeds the Local and SaaS workspaces; it only inserts what's missing.
- **Keeping them in step:** `SchemaSnapshotTest` migrates H2 and compares it with `src/test/resources/schema-snapshot.txt` (tables, columns and nullability, named indexes, foreign keys). The CI integration job (`-Pintegration`, `FlywayMigrationIntegrationTest`) holds PostgreSQL 16 and MySQL 8.4 to the same file. On a mismatch, what was found is written to `target/schema-snapshot.<database>.txt`.
- **`baseline-on-migrate` is off:** a database with tables but no Flyway history is refused, rather than assumed to be current.

### Integrity rules

`V2__integrity.sql` and the Java migration `V3__environment_links` add: case environments as links by ID (`td_test_case_environment`), required plans and collection names, foreign keys from run history (cleared when a plan or environment is deleted), `revision` columns for conflict checks, an audit sequence, Enabled as a boolean, and checks on lifecycle, run trigger, outcome and roles. Checks are written with `CASE`, not `IN (...)`, because of an H2 2.4 bug: see the API's `CLAUDE.md`. The API runs in UTC.

### Changing the schema

1. Add the next version, such as `V2__case_revision.sql`, to **both** folders, in each database's types.
2. Update `schema-snapshot.txt` to match. Run `SchemaSnapshotTest`, and copy `target/schema-snapshot.h2.txt` over it once the change is right.
3. Never edit a migration that has been applied anywhere: Flyway stores a checksum for each one.

### Databases created before the 2026-11-07 baseline

A database migrated with the old V1 to V16 chain stops at startup with `Migration checksum mismatch for migration version 1`, and nothing in it is changed. Don't run Flyway **repair**, as the message suggests: that would record the old schema as the new baseline. It can't be upgraded in place, so start it fresh:

1. In Studio, export the collections and environments you want to keep.
2. Stop the API and drop the database. For local H2, delete `./studio-output/testdata.mv.db` (and `testdata.trace.db` if present).
3. Run `mvn clean` once. Maven doesn't remove deleted files from `target/classes`, so a build that isn't clean still contains the old V2 to V16 migrations and runs them too.
4. Start the API, which builds the new schema, then import what you exported. Accounts, workspace members and run history aren't in the export and start again.

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
