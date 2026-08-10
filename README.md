# Swagger Test Data Generator

Generates `TestData.xlsx` for the [api-automation](D:/code/api-automation) framework from a Swagger 2.0 or OpenAPI 3.x specification.

## Output format

The generated Excel file matches the columns expected by `ExcelUtil` in api-automation:

| Column | Description |
|--------|-------------|
| Test Case No | Sequential row number |
| Service Name | Operation summary / operationId |
| Service Base Url | From OpenAPI `servers[0].url` or `--base-url` |
| Request Type | GET, POST, PUT, PATCH, DELETE |
| Endpoint | Path with sample path params substituted |
| Content-Type | `JSON` or `NA` |
| QueryParamKey / QueryParamValue | Pipe-separated query parameters |
| Header Name / Header Value | Pipe-separated non-auth headers |
| Request Body | `file:src/test/data/payloads/{operation}.json` when a JSON body exists |
| Status Code | First 2xx response code from the spec |
| Response Type | `JSON` for GET, otherwise `NONE` |
| Expected Response | `NA` (fill in after running against real API) |
| Enabled | `Y` by default |
| TestGroup | First OpenAPI tag or `generated` |
| JsonPathAssertions | Sample top-level assertions for GET responses |
| JsonSchemaPath | `file:src/test/data/schemas/{operation}.schema.json` when response schema exists |
| MaxResponseTimeMs | Default `3000` |

Generated JSON payload and schema files are written next to the Excel output unless custom directories are provided.

## Build

```bash
cd D:\code\swagger-testdata-generator
mvn clean package
```

This produces a runnable fat JAR at `target/swagger-testdata-generator-1.0.0-SNAPSHOT.jar`.

## Usage

### From a public Swagger URL

```bash
java -jar target/swagger-testdata-generator-1.0.0-SNAPSHOT.jar ^
  --swagger https://petstore.swagger.io/v2/swagger.json ^
  --output D:\code\api-automation\src\test\data\TestData.xlsx ^
  --payload-dir D:\code\api-automation\src\test\data\payloads ^
  --schema-dir D:\code\api-automation\src\test\data\schemas
```

### From a local OpenAPI file

```bash
java -jar target/swagger-testdata-generator-1.0.0-SNAPSHOT.jar ^
  --swagger D:\specs\employee-service-openapi.yaml ^
  --output D:\code\api-automation\src\test\data\TestData.xlsx ^
  --base-url http://localhost:8080/employee-service/api
```

### Filter operations

```bash
java -jar target/swagger-testdata-generator-1.0.0-SNAPSHOT.jar ^
  --swagger D:\specs\employee-service-openapi.yaml ^
  --output D:\code\api-automation\src\test\data\TestData.xlsx ^
  --include "/employees" ^
  --exclude "delete"
```

## Options

| Option | Description |
|--------|-------------|
| `-s, --swagger` | Swagger/OpenAPI URL or local file (required) |
| `-o, --output` | Output Excel path (default: `./TestData.xlsx`) |
| `-b, --base-url` | Override base URL column |
| `-g, --test-group` | Default test group when operation has no tags |
| `-e, --enabled` | Enabled column value (default: `Y`) |
| `-t, --max-response-time` | Max response time in ms (default: `3000`) |
| `-p, --payload-dir` | Directory for generated request JSON files |
| `--schema-dir` | Directory for generated response JSON Schema files |
| `--payload-prefix` | `file:` path prefix for payloads (default: `src/test/data/payloads`) |
| `--schema-prefix` | `file:` path prefix for schemas (default: `src/test/data/schemas`) |
| `--include` | Regex filter to include matching operations |
| `--exclude` | Regex filter to exclude matching operations |

## Workflow with api-automation

1. Generate `TestData.xlsx`, payloads, and schemas from your Swagger spec.
2. Review and adjust sample values, expected responses, and enabled flags.
3. Run api-automation tests:

```bash
cd D:\code\api-automation
mvn clean test
```

## Notes

- Supports Swagger 2.0 and OpenAPI 3.x via `swagger-parser`.
- Request/response samples are generated from schema `example`, `default`, `enum`, and property types.
- Path parameters named with `id` default to `1`.
- Review generated rows before running against production APIs.
