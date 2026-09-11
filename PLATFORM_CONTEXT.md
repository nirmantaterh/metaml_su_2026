# Platform Context

**Purpose:** This document provides an architectural overview of the MetaML platform ecosystem, delineating the boundaries and responsibilities of the MetaML Workbench relative to companion subsystems. For technical runtime details, see [`docs/architecture/`](docs/architecture/).

---

## 1. System Vision

MetaML is a platform architecture designed for discovering, orchestrating, and evolving AI-augmented business processes across distributed runtime nodes. The platform coordinates capabilities across several architectural layers:

- **BPMN Modeling & Target Platform Generation:** Capturing business process models and generating executable runtime platforms with synchronized twin execution.
- **Taxonomy & Discovery:** Classifying and matching domain tasks to suitable agent implementations.
- **Business Context & Governance:** Enforcing policies, usage constraints, and approval workflows before agent components are introduced into execution flows.
- **Distributed Node Coordination:** Inter-node communication protocols (e.g., BSR/SUR/SUN/MUR message structures) enabling decentralized component discovery and catalog sharing across nodes.

---

## 2. Workbench Architecture & Scope

The **MetaML Workbench** (`metaml-workbench`) is the central modeling, transformation, and governance subsystem of the platform. Implemented with a React frontend and a Spring Boot + Camunda 7 engine backend, the Workbench provides:

1. **BPMN Visual Capture & Validation:** Capturing workflow definitions via a customized `bpmn-js` modeler, incorporating custom extension schemas (`metaml:dataItems`, `metaml:agentOutputs`).
2. **Standalone Target Platform Code Generation:** Compiling BPMN processes into independent Spring Boot applications equipped with Camunda execution engines, RabbitMQ messaging topologies, and automated external-task worker scaffolds.
3. **Synchronized Digital Twin Execution:** Deriving and running synchronized twin processes that mirror production process executions via event-driven messaging and token synchronization.
4. **Agent Integration & Governance:** Providing policy-based controls, deny-lists, and evolution quotas that govern how activities are bound to automated AI agent delegates.
5. **Node Manager Integration:** Interfacing with catalog services to discover and validate candidate agent implementations for process activities.

---

## 3. Core Workbench Capabilities

- **Visual Workflow Modeler:** Custom Camunda-compliant BPMN modeling environment with property inspection and validation.
- **Model-Driven Target Platform Generator:** Generates deployable Spring Boot + Camunda artifacts with full project structure, messaging configuration, and lifecycle management.
- **Process Synchronizer:** Coordinates state between initiator (proxy) and responder (twin) executions over RabbitMQ exchanges with publisher confirmations, dead-letter routing, and correlation handlers.
- **Governance Service:** Enforces per-twin evolution quotas and agent-type authorization policies prior to delegate evolution.
- **Interactive Verification Walkthroughs:** End-to-end integration flows demonstrating manufacturing synchronization, wire-transfer validation, and parallel multi-instance activity lifecycles.
- **Architecture Specification & Decision Records:** Comprehensive system documentation including Architecture Decision Records (ADRs) and runtime interaction models (`docs/architecture/`).

---

## 4. Companion Ecosystem Components

The MetaML ecosystem comprises multiple specialized repositories and subsystems:

- **VS Code Extension (`metaml-vscode-plugin`):** A developer extension providing target platform discovery, live process monitoring, and developer-facing twin evolution workflows connecting to the Workbench governance API.
- **Decentralized Node Network (`p2p`):** The distributed DHT routing network and decentralized catalog exchange layer.
- **Node Manager Service:** The component catalog provider responsible for publishing, querying, and verifying agent capabilities across nodes.

---

## 5. Architectural Roadmap

- Integration with remote decentralized P2P catalog nodes beyond local endpoints.
- Support for Model Context Protocol (MCP) standardized tool and agent interfaces.
- Persistent policy store for governance quotas and audit logs across server restarts.
- Role-based access control (RBAC) and mutual TLS for inter-service communication in distributed deployments.
- Declarative process definition ingestion via structured APIs and CLI tooling alongside the visual modeler.
