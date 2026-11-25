# Send the local API's logs to Elastic Cloud

A step-by-step guide for Windows, without Docker. The Test Data API (SpecPact) writes its logs to a JSON file;
Filebeat, installed on your machine, reads that file and sends every line to your Elastic Cloud deployment; you
search them in Kibana.

```
Test Data API  ──writes──▶  logs\*.json (ECS JSON)  ──read by──▶  Filebeat (D:\tools\filebeat)  ──▶  Elastic Cloud  ──▶  Kibana
```

The console keeps its readable log lines. Elastic credentials live only in Filebeat's keystore: never in the app, a
config file, or the repo.

For the other ways to ship logs (Cloud Run, the local Docker stack) see [LOGGING.md](LOGGING.md).

---

## What you need

- An Elastic Cloud deployment, and a sign-in to its Kibana with admin rights (to create a role and a user).
- The Filebeat **Windows ZIP**, in the same version as your deployment (shown on the deployment page), from
  [elastic.co/downloads/beats/filebeat](https://www.elastic.co/downloads/beats/filebeat).
- The API repo at `D:\code\swagger-testdata-generator`. Adjust the paths below if yours differs.

---

## 1. Create a user for Filebeat in Kibana

Filebeat should write with a user that can only write logs, not with the `elastic` superuser.

1. Open your deployment's Kibana: [cloud.elastic.co](https://cloud.elastic.co) → your deployment → **Open**.
2. **Menu (☰) → Stack Management → Roles → Create role**
   - Role name: `filebeat_writer`
   - Cluster privileges: `monitor`, `read_ilm`, `read_pipeline`, `manage_index_templates`, `manage_ilm`
   - Index privileges: indices `filebeat-*`, privileges `create_doc`, `auto_configure`, `view_index_metadata`
   - **Create role**
3. **Stack Management → Users → Create user**
   - Username: `filebeat_local`
   - A strong password (keep it for step 4)
   - Roles: `filebeat_writer`
   - **Create user**
4. Copy the deployment's **Cloud ID**: on the deployment page in the Elastic Cloud console, under **Cloud ID**
   (a long `name:base64...` string).

---

## 2. Make the API write a JSON log file

Set `TESTDATA_LOG_FILE` in the PowerShell window you start the API from. Every log line then also goes to that file
as one ECS JSON object (Elastic Common Schema); the console is unchanged.

```powershell
cd D:\code\swagger-testdata-generator
$env:TESTDATA_LOG_FILE = "$PWD\logs\testdata-studio.json"
$env:TESTDATA_LOG_ENVIRONMENT = "local"
mvn -pl testdata-generator-api -am spring-boot:run
```

- Use an absolute path (`$PWD\...` gives one): `spring-boot:run` starts in `testdata-generator-api`.
- `logs/` is ignored by git.
- The variables last only for that window. Set them again each time you open a new one.
- The file starts afresh at 50 MB; old files are compressed to `<name>.<date>.<n>.gz` and kept 7 days (500 MB at
  most). Filebeat reads only the live `.json` file.

| Variable | Default | Meaning |
|----------|---------|---------|
| `TESTDATA_LOG_FILE` | (unset: no file) | Path of the JSON log file |
| `TESTDATA_LOG_ENVIRONMENT` | (unset) | `service.environment` on every line, such as `local` |
| `TESTDATA_LOG_FILE_FORMAT` | `ecs` | `ecs`, `logstash` or `gelf` |
| `TESTDATA_LOG_FILE_MAX_SIZE` | `50MB` | Size at which a new file starts |
| `TESTDATA_LOG_FILE_MAX_HISTORY` | `7` | Days of old files kept |
| `TESTDATA_LOG_FILE_TOTAL_SIZE_CAP` | `500MB` | Most space old files may take |

Check: use SpecPact for a moment, then confirm `D:\code\swagger-testdata-generator\logs\testdata-studio.json` exists and
grows.

---

## 3. Install Filebeat

1. Unzip the Filebeat Windows ZIP to `D:\tools\filebeat` (any folder works; avoid spaces in the path), so that
   `D:\tools\filebeat\filebeat.exe` exists.
2. Replace `D:\tools\filebeat\filebeat.yml` with the repo's
   [`examples/elastic/filebeat-cloud.yml`](examples/elastic/filebeat-cloud.yml), or make it exactly this:

```yaml
filebeat.inputs:
  - type: filestream
    id: testdata-studio-api
    enabled: true
    # Single quotes, so YAML keeps the backslashes.
    paths:
      - 'D:\code\swagger-testdata-generator\logs\*.json'
    prospector.scanner.check_interval: 2s
    parsers:
      - ndjson:
          target: ""
          overwrite_keys: true
          expand_keys: true
          add_error_key: true
          message_key: message
    fields_under_root: true
    fields:
      event.dataset: testdata-studio.api

processors:
  - add_fields:
      target: service
      fields:
        environment: local

cloud.id: ${ELASTIC_CLOUD_ID}
cloud.auth: ${ELASTIC_USERNAME}:${ELASTIC_PASSWORD}

logging.level: info
```

Important if you edit Filebeat's own sample `filebeat.yml` instead: its input has `enabled: false` and no `parsers`
section. With `enabled: false` nothing is sent; without the parser every line arrives as one plain text message.

---

## 4. Store the Cloud ID, user name and password in the keystore

`${ELASTIC_CLOUD_ID}`, `${ELASTIC_USERNAME}` and `${ELASTIC_PASSWORD}` in `filebeat.yml` are read from Filebeat's
keystore, an encrypted file at `D:\tools\filebeat\data\filebeat.keystore`.

1. Filebeat reads `filebeat.yml` even for keystore commands, and the values don't exist yet. Put `#` in front of the
   two cloud lines for now:

   ```yaml
   #cloud.id: ${ELASTIC_CLOUD_ID}
   #cloud.auth: ${ELASTIC_USERNAME}:${ELASTIC_PASSWORD}
   ```

2. In PowerShell:

   ```powershell
   cd D:\tools\filebeat
   .\filebeat.exe keystore create
   .\filebeat.exe keystore add ELASTIC_CLOUD_ID
   .\filebeat.exe keystore add ELASTIC_USERNAME
   .\filebeat.exe keystore add ELASTIC_PASSWORD
   ```

   Each `add` asks `Enter value for ...:`. Type or paste the value (right-click or Ctrl+V) and press Enter. Nothing
   shows while you type; that's expected. Paste the Cloud ID without quotes.

3. Check that all three names are there (values are never shown):

   ```powershell
   .\filebeat.exe keystore list
   ```

4. Remove the two `#` you added in step 1 and save `filebeat.yml`.

To change a value later, add it again with `--force`, for example
`.\filebeat.exe keystore add ELASTIC_PASSWORD --force`.

If a terminal doesn't show the prompt (some IDE terminals), pass non-secret values through `--stdin`:
`"filebeat_local" | .\filebeat.exe keystore add ELASTIC_USERNAME --stdin`. Use the prompt for the password, in a
normal PowerShell window, so it doesn't land in your command history.

---

## 5. Test and start Filebeat

```powershell
cd D:\tools\filebeat
.\filebeat.exe test config -c filebeat.yml
.\filebeat.exe test output -c filebeat.yml
```

`test config` should say `Config OK`; `test output` should end with `talk to server... OK`.

Start it (it runs in this window; Ctrl+C stops it):

```powershell
.\filebeat.exe -e -c filebeat.yml
```

Within a few seconds you should see:

- `Harvester started for paths: [D:\code\swagger-testdata-generator\logs\...json]`: it found the file.
- `Connection to backoff(elasticsearch(https://...)) established`: it reached Elastic Cloud.
- Every 30 seconds a `Non-zero metrics` line; `"published"` above 0 means lines were sent.

Filebeat remembers how far it read each file (`D:\tools\filebeat\data\registry`), so after a restart it carries on
where it stopped, and lines written while it was off are sent when it starts again.

### Keep Filebeat running in the background (optional)

Filebeat can run as a Windows service, so it starts with Windows and needs no open window.

1. Open PowerShell **as Administrator**.
2. Install the service (this machine blocks PowerShell scripts by default, so allow it for this one run):

   ```powershell
   cd D:\tools\filebeat
   PowerShell.exe -ExecutionPolicy Bypass -File .\install-service-filebeat.ps1
   ```

3. A service uses its own keystore: run the keystore commands from step 4 again in this Administrator window.
4. Start it: `Start-Service filebeat` (stop with `Stop-Service filebeat`). Its log is in `D:\tools\filebeat\logs`.

To remove it: `.\uninstall-service-filebeat.ps1` from an Administrator PowerShell.

---

## 6. See the logs in Kibana

Sign in to Kibana as yourself (for example the `elastic` user or your own account), not as `filebeat_local`, which
can only write.

### Create a data view (once)

1. **Menu (☰) → Stack Management → Data Views → Create data view**
2. **Name:** `SpecPact API logs` (any label)
3. **Index pattern:** `filebeat-*`. The panel on the right should list a data stream such as `filebeat-8.18.0`.
4. **Timestamp field:** `@timestamp`
5. **Save data view to Kibana**

### Search the logs

**Menu (☰) → Analytics → Discover**, choose **SpecPact API logs** at the top left, and set the time range (top right)
to **Last 15 minutes** or **Today**.

Useful searches in the query bar (KQL):

| Search | Shows |
|--------|-------|
| `service.name : "testdata-studio"` | Only the API's logs |
| `log.level : "ERROR"` or `log.level : ("WARN" or "ERROR")` | Problems |
| `requestId : "<id>"` | One request, using the `X-Request-Id` response header |
| `tenantId : "<workspace id>"` | One workspace |
| `trace.id : "<id>"` | One trace |
| `log.logger : *TestRunService*` | Runs: method, URL, status and time of each request sent |
| `message : *Unresolved*` | Free text in the message |

In the field list on the left, click **+** next to `log.level`, `log.logger`, `message` and `requestId` to show them
as columns. **Save** the search to reopen it later.

Newer Kibana versions also list these logs under **Observability → Logs → Explorer**.

### Fields on each log line

| Field | Meaning |
|-------|---------|
| `@timestamp` | When it was logged |
| `log.level`, `log.logger` | Level and the class that logged it |
| `message` | The log text |
| `service.name`, `service.environment` | `testdata-studio`, and `TESTDATA_LOG_ENVIRONMENT` |
| `requestId` | The request's id (also the `X-Request-Id` response header) |
| `tenantId` | The workspace the request ran in |
| `trace.id`, `span.id` | Tracing ids, when tracing is on |
| `studioReadSource` | `redis` or `db` while the spec cache reads |
| `error.type`, `error.message`, `error.stack_trace` | For logged exceptions |
| `event.dataset` | `testdata-studio.api` (added by Filebeat) |

---

## Troubleshooting

| What you see | Cause and fix |
|--------------|---------------|
| `error initializing beat: cloud.auth specified but cloud.id is empty` when running `keystore add` | `filebeat.yml` uses keystore values that don't exist yet. Comment out the two `cloud.*` lines, add the keys, then uncomment (step 4) |
| `keystore add` shows no prompt | The terminal doesn't allow input. Use a normal PowerShell window, or `--stdin` for non-secret values |
| Kibana: *Name must match one or more data streams, indices, or index aliases* | Nothing has arrived yet, or the pattern is in the wrong box. Check the rows below, then put `filebeat-*` in **Index pattern** (not **Name**). In Dev Tools, `GET _data_stream/filebeat*` lists what exists |
| Filebeat runs but sends nothing; no `Harvester started` line | The input is off or the path is wrong: `enabled: true`, and `paths` in single quotes pointing at the folder of `TESTDATA_LOG_FILE` |
| No log file in `logs\` | The API was started without `$env:TESTDATA_LOG_FILE` in that same window. Set it and start the API again |
| `test output` fails, or `401` / `security_exception: unable to authenticate` | Wrong user name or password in the keystore. Add them again with `--force` |
| `403` / `action [indices:...] is unauthorized` | The Filebeat user's role lacks the privileges in step 1 |
| `GET _data_stream/filebeat*` finds nothing although Filebeat says it published | The Cloud ID is from another deployment. Copy it from the deployment whose Kibana you use, `keystore add ELASTIC_CLOUD_ID --force`, restart Filebeat |
| Each log is one long text `message` instead of fields | The `parsers: - ndjson:` section is missing from the input |
| Discover shows nothing although the data view exists | Widen the time range (lines keep their original time), and sign in to Kibana as a user that can read `filebeat-*` |
| Old lines are sent again after deleting `data\registry` | The registry is Filebeat's reading position. Leave it, or expect duplicates |
| `Unable to find the Filebeat version` / connection errors to `localhost:9200` | `cloud.id` isn't set, so Filebeat falls back to `output.elasticsearch.hosts`. Check the keystore and that the `cloud.id` line isn't commented out |

To see why Filebeat isn't sending, run it in a window with `.\filebeat.exe -e -c filebeat.yml -d "*"` for detailed
output, then go back to the normal command.

---

## Daily use

1. Start the API with `$env:TESTDATA_LOG_FILE` set (step 2).
2. Start Filebeat (`.\filebeat.exe -e -c filebeat.yml`), or let the Windows service run.
3. Open Kibana → **Discover** → **SpecPact API logs**.

To stop sending logs, stop Filebeat (Ctrl+C, or `Stop-Service filebeat`), or start the API without
`TESTDATA_LOG_FILE`.

## Security notes

- The Elastic password and Cloud ID are only in `D:\tools\filebeat\data\filebeat.keystore`. Never put them in
  `filebeat.yml`, a permanent Windows environment variable, or the repo.
- Filebeat uses a user that can only write `filebeat-*`; read the logs in Kibana with your own account.
- Log lines can include request URLs. Avoid putting secrets in query parameters, since anyone who can read
  `filebeat-*` in Kibana sees them.
