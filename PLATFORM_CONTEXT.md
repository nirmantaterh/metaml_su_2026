# Platform Context

**Purpose:** this document exists so nobody reading our showcase material, our code, or this repo mistakes our team's contribution for the whole MetaML platform, or the whole platform for something we invented. It's a companion to [`docs/architecture/`](docs/architecture/) (the runtime architecture itself) — this document is about scope and ownership, not implementation detail.

---

## 1. What MetaML Is, as a Platform

MetaML is a multi-semester NYU Information Technology Projects (ITP) class project. Its long-term vision is a **decentralized platform for discovering, sharing, and reusing AI components** — bots, models, workflows — across a peer-to-peer network of nodes, coordinated through:

- a **taxonomy** layer for classifying and matching components,
- a **business context** layer describing what a component is for,
- a **metrics** layer tracking how well components perform,
- and a **KMP event protocol** (BSR/SUR/SUN/MUR message types) nodes use to request, announce, and update components across the network.

The idea, per the design documentation this project inherits: an application with a capability gap can have its node manager search a local catalog, then reach out across the P2P network for a matching component, and — if nothing exists yet — trigger one being built and registered, all gated by user approval before a node autonomously updates itself.

This is the platform. It spans multiple sub-teams and multiple semesters. **No single team, including ours, owns or has built all of it.**

## 2. How the Workbench Fits Into MetaML

MetaML's own task breakdown splits the platform into six numbered tasks. Our team (Team 3 — Emily Chen, Henry Weisman, Nirman Taterh, Summer 2026) owns Task 1 in full, and pieces of Tasks 2 and 3:

1. **Enhance a workbench application** to capture a business process and deploy it, with a twin, onto a target platform where the two can communicate and the twin can introduce new agent components. *(our primary ownership)*
2. **Improve the MetaML node manager** — client-side integration, server-side improvement, and an MCP-compliant API. *(we own the client-side integration piece only)*
3. **Add a governance API** constraining agent use. *(we own this in full, at the workbench level)*

Concretely, what we built — the **MetaML Workbench** — is a React BPMN modeling tool deployed on a Spring Boot + Camunda 7.22.0 platform. It captures a business process visually, deploys it, and automatically generates a synchronized **Twin** instance whose activities can be automated by pluggable agents, gated by our governance layer. This is one working, demonstrable slice of MetaML's larger vision — proof that the "capture → twin → evolve → govern" loop works — not a rebuild of the platform's P2P/taxonomy/metrics infrastructure.

## 3. What Our Team Owned (and Built)

- Visual BPMN capture (`bpmn-js` + Camunda's own properties panel) integrated into the workbench, with a custom extension schema (`metaml:dataItems`, `metaml:agentOutputs`) for process/task data Camunda doesn't natively carry
- Deploy of a modeled process **and** automatic generation/deployment of its Twin on the same platform
- Real-time Original ↔ Twin synchronization — one shared Camunda engine, one shared datastore, an `AFTER_COMMIT`-triggered bridge, a Receive Task/Service Task pair per activity — verified empirically (probes, `javap` inspection of the builder API, passing/failing regression tests), not just designed on paper
- Two working end-to-end demo processes: a wire-transfer review process, and a multi-reviewer grad admission review process exercising parallel multi-instance activities
- **Node Manager client + a stub server** (`nodemanager` module) simulating an agent-catalog lookup end to end — our slice of Task 2
- **Governance API**: a deny-list of agent types plus a per-twin evolution quota, enforced before every agent assignment, with a usage-view endpoint — our full ownership of Task 3
- A regression suite covering concurrency races, multi-instance graph generation, and synchronization edge cases
- A formal independent architecture review followed by empirical resolution of every finding — 6 Critical issues fixed and tested, 5 of 13 Major issues fixed and tested, 8 of 13 Major issues explicitly documented as accepted trade-offs, all as of 2026-08-04
- The architecture documentation itself: an 11-section Architecture Specification, 15 Architecture Decision Records, an Evolution Timeline, and 7 runtime diagrams (`docs/architecture/`)

## 4. What Existed Before Our Team

- **The real P2P network** — Kademlia DHT/XOR routing, a multi-node deployment — lives in a separate, more mature codebase (`p2p_fall25`) built by an earlier team (Jason Choi, Yuwen Zhong, and others, per their own Fall 2025 report). We have not touched that codebase this semester. Note: that team's own report admits XOR routing doesn't actually route yet ("only local (self) storage lookups work reliably"), while a separate roadmap document claims it was achieved — the two disagree, and neither has been re-verified against current code by us.
- **Taxonomy Management, Business Solutions Management, and Metrics Management** — the other pieces of the "Node Manager" bundle in the platform's own design docs — also live in `p2p_fall25`, not in anything we wrote.
- **The workbench application itself**, in earlier form, predates this semester's work — we enhanced and substantially rebuilt it (BPMN editor integration, twin architecture, governance), we didn't originate the project.
- **Prior-semester showcase material** (e.g. the Fall 2025 "MetaML ver 2025F" flyer, Student Team Jason Choi & Yuwen Zhong) documents that P2P/KMP layer exclusively — a different team, a different codebase, not our prior work, and not a template for what our team has built.

## 5. What Remains Future Work

- Real integration between our Node Manager client and the actual P2P node manager server (rather than our current stub)
- An MCP-compliant version of the MetaML API (Task 2's explicit, unstarted sub-bullet)
- A governance rule engine / multi-tenancy support (explicitly deferred, pending faculty direction)
- Governance quota persistence across app restarts (currently in-memory only — a documented, accepted limitation)
- Production-grade authentication, TLS, and per-task authorization (currently loopback-only, `permitAll`, CSRF disabled — a deliberate demo-scope decision, not an oversight)
- Structured, non-visual (text- or API-driven) process capture, as an alternative to the visual `bpmn-js` editor path
- Tasks 4 (dashboard consolidation) and 5 (KMP → A2A/ACP/ANP protocol compatibility) — not started by any team member this semester
- **Task 6 (VS Code plugin).** A separate repository, `metaml-vscode-plugin`, implements it: Target Platform discovery/lifecycle, Twin/Twin-activity discovery, and AI-driven Twin evolution (natural-language intent → local Ollama recommendation → authoritative Node Manager catalog validation → human confirmation → this Workbench's governance → binding → plugin-triggered bridge → real `ComponentExecutor` execution → real output, readable back through the plugin). See that repository's own README.md for what it does and how it connects here; it is not part of this repository and introduces no new Workbench endpoint beyond `POST /transmute/bridge/{twinId}/{activityId}`, `GET /transmute/twin/{id}/activity/{activityId}/execution` and `POST /transmute/integration-claim`, plus an optional `activityInstanceId` on the existing evolve request — all additive and documented in this repo's `backend/wbapi` controller.

---

*For the empirical detail behind any claim above, see [`docs/architecture/`](docs/architecture/). For terminology and messaging guidance when writing about this project externally, see the companion `MetaML Ground Truth` reference.*
