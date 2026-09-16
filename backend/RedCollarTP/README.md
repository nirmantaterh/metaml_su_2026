# Target Platform

Camunda 7 and Spring Boot process engine application template for generated MetaML Target Platforms.

## Prerequisites

| Requirement | Version | Notes |
|---|---|---|
| JDK | 17 or newer | Generated Target Platforms compile and run on Java 17+. |
| RabbitMQ | any recent | Must be running on `localhost:5672` before starting the application. Start with `docker start metaml-rabbitmq` (or `docker run -d --name metaml-rabbitmq -p 5672:5672 rabbitmq:3-management` on first use). |

The Target Platform is **standalone** — it does not require the MetaML Workbench backend or frontend to be running.

## Architecture

The platform runtime implements a synchronized **Proxy / Twin** architecture:
- `proxy` - outward-facing process delegates and execution listeners that receive commands and drive primary process progression.
- `twin` - operational digital-twin mirror kept in lockstep with the proxy process via RabbitMQ messaging and intermediate catch events.
- `messaging` - AMQP queue configuration, publishers, and listeners providing decoupled process instance coordination.
- `status` - REST controllers for process introspection and runtime status reporting.

## Configuration

Core properties are defined in `src/main/resources/application.properties`:
- Port and context path (`server.port=8080`)
- Camunda engine execution settings
- In-memory H2 database (or configurable file-backed datasource)
- RabbitMQ messaging topology (`metaml.messaging.enabled=true`)

## Build & Run

The generated Target Platform is independently runnable from the Workbench once required MetaML runtime artifacts are available locally. The Maven Wrapper
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

## After Startup

Once the application has started, open:

| URL | Description | Credentials |
|---|---|---|
| <http://localhost:8080/> | MetaML Target Platform portal — process dashboard and runtime status | — |
| <http://localhost:8080/camunda> | Camunda Tasklist / Cockpit | `demo` / `demo` |
