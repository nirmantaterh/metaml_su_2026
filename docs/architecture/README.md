# Digital Twin Runtime Architecture — Documentation Index

Permanent architectural record for the MetaML Workbench Digital Twin runtime, Version 1.0. Four deliverables:

1. **[Architecture Specification](ARCHITECTURE.md)** — the complete 11-section specification: executive summary, runtime architecture, system lifecycle, synchronization architecture, BPMN transformation rules, execution identity model, failure semantics, component responsibilities, known limitations, future work, and verification summary.
2. **[Architecture Decision Records](adr/)** — 15 ADRs, each with status, context, decision, alternatives investigated, verification, trade-offs, consequences, and future reconsideration.
3. **[Evolution Timeline](EVOLUTION_TIMELINE.md)** — the record of architectural design iterations, Camunda discoveries, key pivots, and technical verifications that established this architecture.
4. **[Runtime Diagrams](DIAGRAMS.md)** — 7 Mermaid diagrams: runtime components, runtime sequence, synchronization sequence, BPMN transformation, execution identity resolution, failure recovery, multi-instance synchronization.

This documentation describes the system architecture and implementation as verified against `backend/workbench` and `backend/wbapi`.
