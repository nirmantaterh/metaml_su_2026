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

To build and run the application locally:

```bash
./mvnw clean compile
./mvnw spring-boot:run
```
