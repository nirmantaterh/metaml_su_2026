# Generated RedCollar Manufacturing Target Platform Application

This directory contains a complete, standalone Spring Boot + Camunda 7 Target Platform application generated directly by the MetaML `SpringBootProjectGenerator` from the canonical RedCollar garment manufacturing process models.

## Source Process
- **Proxy Process:** `RedCollar.Manuf` (`src/main/resources/processes/RedCollar.Manuf.bpmn`)
- **Twin Process:** `RedCollar.Twin` (`src/main/resources/processes/RedCollar.Twin.bpmn`)
- **Template Scaffold:** `backend/RedCollarTP/`
- **Generator Engine:** `com.metaml.workbench.generation.SpringBootProjectGenerator`

## Architecture & Generated Components

The generated application implements the full dual-engine **Proxy / Twin** architecture for runtime process execution and simulation:

1. **Root Application & Lifecycle**
   - `RedcollarManufacturingApplication.java`: Spring Boot entrypoint with Camunda Process Engine auto-configuration.
   - `config/`: Camunda engine configuration, H2 database properties, CORS headers, and process properties.

2. **Proxy Process Layer (`proxy/`)**
   - `ProxyProcessController.java`: REST controller exposing endpoints to start and inspect external manufacturing process instances (`/api/proxy/start`).
   - `worker/proxy/`: Camunda external task workers for each manufacturing step (`CuttingWorker`, `LayingWorker`, `StitchingWorker`, `SamplingWorker`, `CheckingWorker`, `MarkingWorker`, `PackagingWorker`, `PressingWorker`, `ShippingWorker`, `EditOrderDetailsWorker`, `OrderMgmtInitializationWorker`, `VerifyOrderWorker`).

3. **Twin Process Layer (`twin/`)**
   - `TwinProcessController.java`: REST endpoints for controlling the mirrored Twin Process (`/api/twin/start`).
   - `worker/twin/`: Twin external task workers and ML decision agents (`CuttingTwinWorker`, `LayingTwinWorker`, `StitchingTwinWorker`, `SamplingTwinWorker`, `CheckingTwinWorker`, `MarkingTwinWorker`, `PackagingTwinWorker`, `PressingTwinWorker`, `ShippingTwinWorker`, `OrderMgmtInitializationTwinWorker`, `TwinDecisionAgent`).

4. **Inter-Engine Coordination & Messaging (`coordination/` & `messaging/`)**
   - `PairRegistry.java`: Correlates proxy instance IDs with twin instance IDs using shared business keys.
   - `RabbitMqConfig.java`, `TaskQueueListener.java`, `ResponseQueueListener.java`, `TaskQueuePublisher.java`, `ResponseQueuePublisher.java`: AMQP queue messaging infrastructure enabling lockstep synchronization.
   - `signal/SignalBroadcaster.java`: Dispatches intermediate catch signals across engines for all 9 manufacturing stages (`cuttingSignal`, `layingSignal`, `stitchingSignal`, `samplingSignal`, `checkingSignal`, `markingSignal`, `packagingSignal`, `pressingSignal`, `shippingSignal`).

5. **External Task Poller (`worker/`)**
   - `ExternalTaskPoller.java`, `GeneratedExternalTaskWorker.java`, `SchedulingConfig.java`: Scheduled background poller subscribing to Camunda external task topics.

6. **Process Status & Inspection (`status/`)**
   - `GeneratedProcessStatusController.java`: REST endpoints inspecting runtime process token positions and activity state.

## Building & Running

The application is a standard Maven Spring Boot project:

```bash
# Compile and package
mvn clean package

# Run standalone
mvn spring-boot:run
```
