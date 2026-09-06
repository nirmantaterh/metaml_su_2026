# Digital Twin Runtime Architecture — Documentation Index

Permanent architectural record for the MetaML Workbench Digital Twin runtime, Version 1.0. Four deliverables:

1. **[Architecture Specification](ARCHITECTURE.md)** — the complete 11-section specification: executive summary, runtime architecture, system lifecycle, synchronization architecture, BPMN transformation rules, execution identity model, failure semantics, component responsibilities, known limitations, future work, and verification summary.
2. **[Architecture Decision Records](adr/)** — 15 ADRs, each with status, context, decision, alternatives investigated, evidence, trade-offs, consequences, and future reconsideration.
3. **[Evolution Timeline](EVOLUTION_TIMELINE.md)** — the chronological record of assumptions, disproven assumptions, Camunda discoveries, architectural pivots, rejected alternatives, significant bugs, empirical investigations, and adversarial reviews that produced this architecture.
4. **[Runtime Diagrams](DIAGRAMS.md)** — 7 Mermaid diagrams: runtime components, runtime sequence, synchronization sequence, BPMN transformation, execution identity resolution, failure recovery, multi-instance synchronization.

This documentation was written entirely after implementation — it describes the system exactly as it exists, verified directly against the current source of `backend/workbench` and `backend/wbapi` at the time of writing, not as a plan or a proposal. No code was changed to produce it.
