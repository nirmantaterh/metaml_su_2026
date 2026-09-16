# MetaML

MetaML is a Workbench for creating BPMN process models and generating runnable Spring Boot projects.

---

## Requirements

- **Java JDK 24 or newer** (for building and running the backend)
- **Node.js and npm** (for the frontend)
- **Docker** (for running RabbitMQ)
- **Git**

---

## Quick Start

### RabbitMQ

RabbitMQ coordinates messaging between process components.

**First-time creation:**
```bash
docker run -d --name metaml-rabbitmq -p 5672:5672 -p 15672:15672 rabbitmq:3-management
```

**Later starts:**
```bash
docker start metaml-rabbitmq
```

Management console: [http://localhost:15672](http://localhost:15672) (default login: `guest` / `guest`).

---

### Backend

The backend is located in the `backend/` directory.

#### First Run (Fresh Clone)

On a fresh clone, install the backend modules into your local Maven cache so that sibling dependencies are available:

- **Windows PowerShell:**
  ```powershell
  cd backend
  .\mvnw.cmd install -DskipTests
  ```
- **Linux / macOS:**
  ```bash
  cd backend
  ./mvnw install -DskipTests
  ```

*(On Windows, `.\run-wbapi.cmd` is also available as a convenience launcher to install dependencies and run the backend).*

#### Normal Startup

Start the backend API server (`wbapi`):

- **Windows PowerShell:**
  ```powershell
  cd backend
  .\mvnw.cmd -pl wbapi spring-boot:run
  ```
- **Linux / macOS:**
  ```bash
  cd backend
  ./mvnw -pl wbapi spring-boot:run
  ```

The backend starts on [http://localhost:8082](http://localhost:8082).
Verify it is running: [http://localhost:8082/api/v1/projects/all](http://localhost:8082/api/v1/projects/all)

---

### Frontend

The frontend is located in the `frontend/` directory.

```bash
cd frontend
npm install
npm start
```

The web interface opens at [http://localhost:3000](http://localhost:3000).

---

## Basic Workflow

1. Open or create a project in the Workbench.
2. Create or edit a BPMN process model.
3. Save the process model.
4. Generate the application.
5. After generation, open the generated project directory.
6. Build and run the generated project.

---

## Run a Generated Project

Each generated project includes its own Maven Wrapper and runs as a separate Spring Boot application.

> **Note:** Generated projects depend on MetaML modules such as `capability-core` and `reference-providers`. On a fresh clone, complete the backend install step before building a generated project.

1. **Open the generated project directory:**
   ```bash
   cd <path-to-generated-project>
   ```

2. **Build the project:**
   - **Windows PowerShell:**
     ```powershell
     .\mvnw.cmd clean compile
     ```
   - **Linux / macOS:**
     ```bash
     ./mvnw clean compile
     ```

3. **Run the application:**
   - **Windows PowerShell:**
     ```powershell
     .\mvnw.cmd spring-boot:run
     ```
   - **Linux / macOS:**
     ```bash
     ./mvnw spring-boot:run
     ```

4. **Access the running application:**
   - Application: [http://localhost:8080](http://localhost:8080)
   - Camunda: [http://localhost:8080/camunda](http://localhost:8080/camunda) (default login: `demo` / `demo`)

---

## Tests

### Backend Tests

Run unit and integration tests across backend modules:

- **Windows PowerShell:**
  ```powershell
  cd backend
  .\mvnw.cmd test
  ```
- **Linux / macOS:**
  ```bash
  cd backend
  ./mvnw test
  ```

### Frontend Tests

Run the React test suite:

```bash
cd frontend
npm test -- --watchAll=false
```

---

## Repository Structure

- `backend/` — Multi-module Maven project for backend services:
  - `wbapi/` — Main Spring Boot REST API application.
  - `workbench/` — Process management, modeling services, and project generator.
  - `capability-core/` — Shared capability module.
  - `providers/` — Reference provider module (`artifactId: reference-providers`).
  - `nodemanager/` — Node manager module.
  - `RedCollarTP/` — Application template used to scaffold generated projects.
- `frontend/` — React single-page web application.
- `docs/` — Architecture documentation, ADRs, and diagrams.

---

## Architecture Documentation

For in-depth architectural details, refer to the documentation in `docs/architecture/`:

- [Documentation Index](docs/architecture/README.md)
- [Architecture Specification](docs/architecture/ARCHITECTURE.md)
- [Architecture Decision Records (ADRs)](docs/architecture/adr/)
- [Runtime Diagrams](docs/architecture/DIAGRAMS.md)
- [Evolution Timeline](docs/architecture/EVOLUTION_TIMELINE.md)
