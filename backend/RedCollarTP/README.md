# Target Platform Template (RedCollarTP)

Camunda 7 and Spring Boot process engine application template for generated MetaML Target Platforms.

## Architecture

The platform runtime implements a synchronized **Proxy / Twin** architecture:
- `proxy` — outward-facing process delegates and execution listeners that receive commands and drive primary process progression.
- `twin` — operational digital-twin mirror kept in lockstep with the proxy process via RabbitMQ messaging and intermediate catch events.
- `messaging` — AMQP queue configuration, publishers, and listeners providing decoupled process instance coordination.
- `status` — REST controllers for process introspection and runtime status reporting.

## Configuration

Core properties are defined in `src/main/resources/application.properties`:
- Port and context path (`server.port=8080`)
- Camunda engine execution settings
- In-memory H2 database (or configurable file-backed datasource)
- RabbitMQ messaging topology (`metaml.messaging.enabled=true`)

## Build & Run

The generated Target Platform can run independently of the MetaML Workbench. The Maven Wrapper
scripts are included in every generated project; their first use downloads Maven if needed.

### Windows

```powershell
.\mvnw.cmd clean compile
.\mvnw.cmd spring-boot:run
```

### macOS and Linux

```bash
./mvnw clean compile
./mvnw spring-boot:run
```

The application runs in the foreground. Press `Ctrl+C` in that terminal to stop a separately
launched Target Platform. When launched from **Transmute -> Launch**, use that page's **Stop**
control; it stops the Workbench-managed process and its child process tree.

## Standalone Execution

Starting the Target Platform application is not the same as starting a correlated process pair.
With the default configuration, open the standalone portal at <http://localhost:8080>.

The preferred reviewer workflow is:

**Open the portal -> Start New Run -> Live Execution / Runtime / Communication**

The portal's **Start New Run** action starts the real Proxy and Twin endpoints with one shared
business key and then configures the selected scenario. It does not use an unpaired process-start
path.

For a lower-level API check, use PowerShell and the same key for both sides:

```powershell
$key = 'demo-001'

Invoke-RestMethod -Method Post "http://localhost:8080/api/proxy/start?businessKey=$key&executionMode=STEP"
Invoke-RestMethod -Method Post "http://localhost:8080/api/twin/start?businessKey=$key"
```

Both endpoints require **POST**. Pasting either URL into a browser address bar sends a GET and
correctly returns `405 Method Not Allowed`. The GET health checks are
`/api/proxy/health` and `/api/twin/health`.

Before a pair starts, Runtime showing zero Proxy/Twin events is expected; system or platform
startup events may still be present. Runtime entries are this generated Target Platform JVM's
actual logged activity, with UI event-kind and Proxy/Twin attribution layered over those records;
the portal does not create a separate event stream.

At a high level, the `businessKey` identifies one correlated Proxy/Twin run. RabbitMQ carries
TASK/RESPONSE messages, while `PairRegistry` records the Proxy and Twin instances that belong to
that run. `SignalBroadcaster` together with Camunda's waiting and signal behavior coordinates
waiting, release, and progression. The business key is not a RabbitMQ queue, and RabbitMQ alone
does not provide synchronization.

### Generated synchronization rendezvous

A generated `sync_evt_*` intermediate catch event can intentionally appear after an eligible
activity and before its next outgoing BPMN element, including an End event. It is a technical
synchronization rendezvous, not a business task. In the current RedCollar model, Shipping goes
directly to End because Shipping is not selected by that generator path; this does not change the
generated catch-event behavior for eligible activities.
