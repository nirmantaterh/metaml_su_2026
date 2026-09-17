# Generated Target Platforms

This directory contains standalone, fully completed Spring Boot + Camunda 7 Target Platform applications produced by the MetaML `SpringBootProjectGenerator` from the canonical `backend/RedCollarTP/` template.

## Available Generated Applications

1. [`redcollar-manufacturing/`](redcollar-manufacturing/)
   - **Canonical RedCollar Target Platform:** Generated from the multi-model RedCollar garment manufacturing process (`Manuf-camunda.bpmn` + `Twin-camunda.bpmn`).
   - Implements 9 lockstep manufacturing activities (`Cutting`, `Laying`, `Stitching`, `Sampling`, `Checking`, `Marking`, `Packaging`, `Pressing`, `Shipping`) synchronized over RabbitMQ AMQP exchanges and intermediate catch signals.

2. [`liveverify-wiretransfer/`](liveverify-wiretransfer/)
   - **Wire Transfer Target Platform:** Generated from the single-model `LiveVerify-WireTransfer` banking process (`Process_WireTransfer.bpmn`), with mirrored Twin process derived via `TwinModelGenerator`.
   - Used to verify dynamic agent discovery (`NodeManagerClient`), Ollama AI candidate recommendation, governance policy enforcement, and runtime execution dispatch to pluggable component executors.

## Architecture Distinction

- **Template Scaffold:** [`backend/RedCollarTP/`](../backend/RedCollarTP/) provides the base Spring Boot 4.1.0 + Camunda 7.24.0 + AMQP Maven structure.
- **Generator Engine:** [`backend/workbench/src/main/java/com/metaml/workbench/generation/`](../backend/workbench/src/main/java/com/metaml/workbench/generation/) transforms BPMN process models into complete, runnable Target Platforms.
- **Generated Applications:** The subdirectories above represent finished, self-contained applications ready for independent compilation and execution.
