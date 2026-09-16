# Generated MetaML Target Platform Application

This directory contains a complete, self-contained Spring Boot + Camunda 7 Target Platform application produced directly by the MetaML `SpringBootProjectGenerator`.

## Source Process
- **Process Name:** LiveVerify-WireTransfer
- **Process Key:** `Process_WireTransfer`
- **Source Generator:** `com.metaml.workbench.generation.SpringBootProjectGenerator`

## Architecture & Generated Components

The generated application implements the dual-engine **Proxy / Twin** architecture for runtime process transmutations:

1. **Root Application & Lifecycle**
   - `LiveverifyWiretransfer2Application.java`: Spring Boot main application entrypoint with Camunda Process Engine auto-configuration.
   - `config/`: Camunda engine configuration, H2 in-memory datasource properties, CORS headers, and process properties.

2. **Proxy Process Layer (`proxy/`)**
   - `ProxyProcessController.java`: REST controller exposing endpoints to start, inspect, and complete external user-facing process instances.
   - `listeners/AgentExecutionDelegate.java`: Execution listeners capturing process lifecycle events on proxy activities.

3. **Twin Process Layer (`twin/`)**
   - `TwinProcessController.java`: REST endpoints for controlling and advancing the mirrored Twin Process.
   - `listeners/AgentExecutionDelegateTwin.java`: Twin execution delegates and listeners dispatching automation logic.

4. **Inter-Engine Coordination & Messaging (`coordination/` & `messaging/`)**
   - `PairRegistry.java`: Correlates proxy instance IDs with their corresponding twin instance IDs.
   - `RabbitMqConfig.java`, `TaskQueueListener.java`, `ResponseQueueListener.java`, `TaskQueuePublisher.java`, `ResponseQueuePublisher.java`: RabbitMQ broker queues enabling state synchronization between proxy and twin processes.
   - `signal/SignalBroadcaster.java`: Dispatches shared BPMN intermediate signal events across engines.

5. **External Task Workers (`worker/`)**
   - `ExternalTaskPoller.java`, `GeneratedExternalTaskWorker.java`, `SchedulingConfig.java`: Scheduled background pollers handling asynchronous Camunda external task topics.

6. **Process Definitions (`src/main/resources/processes/`)**
   - `Process_WireTransfer.bpmn`: The primary proxy workflow definition.
   - `Process_WireTransfer_twin.bpmn`: The generated mirrored Twin workflow definition.

## Building & Running

The application is a standard Maven Spring Boot project:

```bash
# Compile and package
mvn clean package

# Run standalone
mvn spring-boot:run
```
