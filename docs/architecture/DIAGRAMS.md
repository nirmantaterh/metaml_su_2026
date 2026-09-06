# Runtime Documentation Diagrams

Seven diagrams covering the runtime as implemented in Version 1.0. All render natively as GitHub-flavored Mermaid. Cross-referenced from [ARCHITECTURE.md](ARCHITECTURE.md).

---

## 1. Runtime Component Diagram

Every major class in the runtime and how they depend on each other, grouped by which side of the Original/Twin boundary they act on.

```mermaid
flowchart TB
    Client["REST client\n(frontend / API caller)"]

    subgraph WBAPI["backend/wbapi — Spring Boot host"]
        WC["WorkbenchController"]
        GC["GovernanceController"]
    end

    subgraph WB["backend/workbench — domain logic"]
        WSI["WorkbenchServiceImpl"]
        TMG["TwinModelGenerator"]
        ABT["AutoBridgeTrigger\n(AFTER_COMMIT listener)"]
        AED["AgentExecutionDelegate\n(Original task listener)"]
        TAD["TwinAutomationDelegate\n(Twin service task)"]
        PAS["ProjectAutomationService\n(pluggable, per-project)"]
        GSI["GovernanceServiceImpl"]
        WSS["WorkbenchStateStore\n(JSON: twins only)"]
        NMC["NodeManagerClient"]
        AOD["AgentOutputDeclarations"]
    end

    subgraph ENGINE["Camunda 7.22 Engine — one shared H2 datasource"]
        RS["RuntimeService / RepositoryService\n/ TaskService / HistoryService"]
        ORIG["Original ProcessInstance\n(human user tasks)"]
        TWIN["Twin ProcessInstance\n(Receive/Service Task pairs)"]
    end

    NM["External Node Manager\n(stub agent catalog, :8083)"]

    Client --> WC
    Client --> GC
    WC --> WSI
    GC --> GSI
    GC --> WSI

    WSI --> TMG
    WSI --> GSI
    WSI --> WSS
    WSI --> RS
    WSI --> AOD
    TMG --> RS

    RS --> ORIG
    RS --> TWIN

    ORIG -. "activity start event\n(AFTER_COMMIT)" .-> ABT
    ABT --> WSI

    ORIG -. "task complete listener" .-> AED
    AED --> WSI
    AED --> RS

    TWIN -. "service task execution" .-> TAD
    TAD --> PAS
    TAD --> RS

    WSI --> NMC
    NMC --> NM

    style ORIG fill:#e8f4fd,stroke:#2b6cb0
    style TWIN fill:#fdf3e7,stroke:#c05621
```

---

## 2. Runtime Sequence Diagram

The full lifecycle from launch through one synchronized step, in call order.

```mermaid
sequenceDiagram
    actor Op as Operator
    participant WC as WorkbenchController
    participant WSI as WorkbenchServiceImpl
    participant TMG as TwinModelGenerator
    participant RS as Camunda RuntimeService
    participant ORIG as Original Instance
    participant TWIN as Twin Instance
    participant ABT as AutoBridgeTrigger

    Op->>WC: POST /transmute/launch
    WC->>WSI: launchProcess(modelId)
    WSI->>TMG: generate(originalBpmnModel)
    TMG-->>WSI: twin BpmnModelInstance
    WSI->>RS: deploy twin (duplicate filtering)
    WSI->>RS: startProcessInstanceById(original)
    RS-->>ORIG: instance started, businessKey="original-[twinId]"
    WSI->>RS: startProcessInstanceById(twin)
    RS-->>TWIN: instance started, businessKey="twin-[twinId]"
    WSI-->>WC: TwinProcess

    Op->>WC: POST /transmute/connect (link activities)
    WC->>WSI: connectActivity(...)

    Note over Op,TWIN: Original's first task has no start-event listener yet reachable — bridged manually once
    Op->>WC: POST /transmute/bridge/{twinId}/{activityId}
    WC->>WSI: bridgeActivityEvent(twinId, activityId)
    WSI->>TWIN: advance via message correlation
    TWIN-->>WSI: Service Task ran, next Receive Task waiting

    Op->>WC: POST /transmute/complete-task/{twinId}
    WC->>WSI: completeCurrentTasks(twinId)
    WSI->>ORIG: taskService.complete(...)
    ORIG-->>ORIG: transaction commits

    Note over ORIG,ABT: AFTER_COMMIT — see Synchronization Sequence Diagram for detail
    ORIG--)ABT: ExecutionEvent (activity start)
    ABT->>WSI: bridgeActivityEvent(twinId, activityId, activityInstanceId)
    WSI->>TWIN: evolve + advance
    TWIN-->>WSI: next Receive Task waiting
```

---

## 3. Synchronization Sequence Diagram

What happens between an Original commit and the Twin's next wait state, in full detail — the core mechanism this whole architecture is built around ([ADR-004](adr/ADR-004-event-driven-synchronization.md), [ADR-005](adr/ADR-005-receive-service-task-separation.md)).

```mermaid
sequenceDiagram
    participant ORIG as Original Execution
    participant SpringTx as Spring Transaction\nSynchronization
    participant ABT as AutoBridgeTrigger
    participant Exec as bridgeExecutor\n(single thread)
    participant WSI as WorkbenchServiceImpl
    participant NM as NodeManagerClient
    participant RS as RuntimeService
    participant RT as Twin Receive Task
    participant ST as Twin Service Task
    participant TAD as TwinAutomationDelegate

    ORIG->>ORIG: activity starts, command commits
    ORIG--)SpringTx: ExecutionEvent published
    SpringTx->>ABT: onActivityStarted (AFTER_COMMIT phase)
    ABT->>ABT: filter: businessKey starts "original-"?
    ABT->>Exec: submit(runBridge)
    Exec->>WSI: bridgeActivityEvent(twinId, activityId, activityInstanceId)

    rect rgb(232, 244, 253)
        Note over WSI,NM: Bridge (evolve)
        WSI->>WSI: resolve twin activity via ActivityLink
        WSI->>WSI: alreadyEvolved? (derived from Camunda history)
        WSI->>NM: checkAgentAvailability(agentType)
        NM-->>WSI: agent name + outputs
        WSI->>RS: setVariable(evolvedAgent_*, evolvedAgentOutput_*)
    end

    rect rgb(253, 243, 231)
        Note over WSI,TAD: Advance (one Camunda command, no async marker)
        WSI->>RS: messageEventReceived(...) or correlate()
        RS->>RT: message consumed, Receive Task ends
        RS->>ST: Service Task starts (synchronous)
        ST->>TAD: execute(execution)
        TAD->>TAD: resolve sync activity id from own id
        TAD->>TAD: dispatch to ProjectAutomationService
        TAD->>RS: setVariable(twinAutomation_*, twinAutomationOutput_*)
        RS->>RT: token lands on NEXT Receive Task
    end

    WSI-->>Exec: TwinAdvance(advanced=true)
    Exec-->>ABT: bridged.get() completes
```

---

## 4. BPMN Transformation Diagram

How `TwinModelGenerator` classifies and transforms every construct it encounters ([ADR-011](adr/ADR-011-unsupported-bpmn-construct-policy.md)).

```mermaid
flowchart TD
    Start(["Original BPMN node\nreached during graph walk"])
    Start --> Check{"isSupported(node)?"}

    Check -->|"UserTask, no loop"| UT["Receive Task\n(TwinAdvance_&lt;id&gt;)\n→ Service Task\n(twinAutomationDelegate)"]
    Check -->|"UserTask, literal-cardinality MI"| MI["Embedded sub-process\nwrapping [Receive, Service]\n+ multiInstance(sequential/parallel)"]
    Check -->|"UserTask, non-literal cardinality MI"| MIFallback["Same as plain UserTask\n(single visit, logged warning)"]
    Check -->|"Exclusive / Parallel / Inclusive Gateway"| GW["Copied as-is\n(default flows preserved)"]
    Check -->|"Plain End Event"| EE["Copied as-is"]

    Check -->|"Boundary Event\n(source of a flow)"| BE["Dropped, with its flow —\ndeliberate, see ADR-011"]

    Check -->|"End Event w/ event definition,\nEvent-Based Gateway, Call Activity,\nSub-Process, pre-existing\nService/Script/Receive/Send/\nBusinessRule/ManualTask"| Fail["generate() throws\nIllegalArgumentException\n(process, activity id, element type)"]

    Check -->|"Ad-Hoc Sub-Process"| Limit["🚫 Cannot be built at all —\nclass absent from\ncamunda-bpmn-model 7.22.0"]

    UT --> Stabilize["stabilizeMessageIds\nstabilizeMultiInstanceIds\nstripDiagramInterchange"]
    MI --> Stabilize
    GW --> Stabilize
    EE --> Stabilize

    Stabilize --> Deploy["Deterministic twin\nBpmnModelInstance\n(byte-identical across calls)"]

    style Fail fill:#fde2e1,stroke:#c53030
    style Limit fill:#fde2e1,stroke:#c53030
    style BE fill:#fef3d5,stroke:#c05621
    style Deploy fill:#e6f4ea,stroke:#2f855a
```

---

## 5. Execution Identity Resolution Diagram

How "which specific visit, which execution, which loop iteration" gets answered fresh on every synchronization event — never cached, except the one `ActivityLink` mapping ([ADR-006](adr/ADR-006-runtime-derived-execution-identity.md)).

```mermaid
flowchart TD
    Event["ExecutionEvent\n(activityId, activityInstanceId\nfrom the Original)"]

    Event --> LinkLookup["ActivityLink lookup\n(the ONE persisted mapping)"]
    LinkLookup --> TwinActivityId["twinActivityId"]

    Event --> VisitWalk["Walk ActivityInstance tree\n(getActivityInstances(activityId))\n— NOT createExecutionQuery() +\ngetActiveActivityIds()"]
    VisitWalk --> ExecId["This visit's executionId"]

    ExecId --> LoopLocal["Original side:\ngetVariableLocal(execId, loopCounter)\n— local to the start event's own execution"]

    TwinActivityId --> MsgName["messageName =\nTwinAdvance_&lt;twinActivityId&gt;"]
    MsgName --> SubQuery["createEventSubscriptionQuery()\non Twin process instance"]
    SubQuery --> Candidates{"How many\nsubscribed\nexecutions?"}

    Candidates -->|"1 (plain / sequential MI)"| SingleTarget["Scoped correlate()\n— no ambiguity possible"]
    Candidates -->|">1 (parallel MI)"| LoopNonLocal["Twin side, per candidate:\ngetVariable(execId, loopCounter)\n— NON-local, one scope above\nthe event-subscribed execution"]

    LoopLocal --> Match{"originalLoopCounter\n== candidate's\nloopCounter?"}
    LoopNonLocal --> Match
    Match -->|yes| Target["messageEventReceived(\nmsgName, thatExecutionId)"]
    Match -->|"no match found"| Fallback["Fall back to single-\ncandidate path, logged"]

    SingleTarget --> Advance["Twin token advances"]
    Target --> Advance

    style LinkLookup fill:#e6f4ea,stroke:#2f855a
    style VisitWalk fill:#e8f4fd,stroke:#2b6cb0
    style LoopNonLocal fill:#fdf3e7,stroke:#c05621
    style LoopLocal fill:#fdf3e7,stroke:#c05621
```

---

## 6. Failure Recovery Diagram

What happens when the Twin's automation throws, from the moment of failure through operator-driven recovery ([ADR-008](adr/ADR-008-incident-driven-failure-policy.md), [ADR-012](adr/ADR-012-restart-and-recovery-philosophy.md)).

```mermaid
flowchart TD
    Correlate["messageEventReceived() /\ncorrelate() issued"]
    Correlate --> RTEnds["Receive Task's subscription\nconsumed (tentatively)"]
    RTEnds --> STRuns["Service Task runs\nTwinAutomationDelegate.execute()"]
    STRuns --> PAS["ProjectAutomationService.execute()"]

    PAS -->|"succeeds"| Commit["Whole command commits:\nRT consumed, ST ran,\ntwinAutomation_* written,\ntoken on NEXT Receive Task"]

    PAS -->|"throws"| Rollback["ENTIRE Camunda command\nrolls back — RT's own\ncorrelation included"]
    Rollback --> Untouched["Receive Task's event\nsubscription: UNCHANGED,\nexactly as before the attempt"]
    Untouched --> Release["governanceService\n.releaseTwinExecutionSlot()"]
    Release --> FindExec["findWaitingExecutionId /\nknown executionId\n(ActivityInstance tree walk)"]
    FindExec --> Incident["runtimeService.createIncident(\n'twinAutomationFailure',\nexecutionId, activityId, message)"]
    Incident --> Visible["Incident visible in Cockpit.\nTwin paused. Original: unaffected."]

    Visible --> OperatorAction{"Operator\nre-bridges\nthe activity"}
    OperatorAction --> Derived["alreadyEvolved() checked —\nderived from Camunda history,\nNOT app memory (ADR-012)"]
    Derived -->|"evolve already succeeded\n(variable was set pre-failure)"| SkipEvolve["Skip re-evolution\n('already forwarded')"]
    SkipEvolve --> Retry["advanceTwinActivity retried"]
    Retry --> PAS

    style Rollback fill:#fde2e1,stroke:#c53030
    style Incident fill:#fef3d5,stroke:#c05621
    style Commit fill:#e6f4ea,stroke:#2f855a
    style Derived fill:#e8f4fd,stroke:#2b6cb0
```

---

## 7. Multi-Instance Synchronization Diagram

Sequential and parallel Multi-Instance, side by side — how N visits/siblings on the Original map to N visits/siblings on the Twin ([ADR-007](adr/ADR-007-execution-targeted-messaging.md), [ADR-010](adr/ADR-010-sequential-and-parallel-multi-instance-support.md)).

```mermaid
flowchart TB
    subgraph SEQ["Sequential Multi-Instance"]
        direction TB
        S0["Original visit 0\nloopCounter=0"] --> S1["Original visit 1\nloopCounter=1"]
        S0 -.bridge.-> ST0["Twin visit 0\n(only one Receive Task\nwaiting at a time)"]
        S1 -.bridge.-> ST1["Twin visit 1\n(same Receive Task,\nnew iteration)"]
        ST0 --> ST1
        Note1["Single candidate always —\nscoped correlate() suffices,\nno disambiguation needed"]
    end

    subgraph PAR["Parallel Multi-Instance"]
        direction TB
        P0["Original sibling 0\nloopCounter=0"]
        P1["Original sibling 1\nloopCounter=1"]
        P2["Original sibling 2\nloopCounter=2"]

        P0 -.start event.-> Trig["AutoBridgeTrigger\n(fires once per sibling,\nas each starts)"]
        P1 -.start event.-> Trig
        P2 -.start event.-> Trig

        Trig --> Resolve["resolveParallelSibling:\nmatch originalLoopCounter\nagainst each waiting Twin\nsibling's own loopCounter"]

        Resolve --> PT0["Twin sibling 0\n(released independently)"]
        Resolve --> PT1["Twin sibling 1\n(released independently)"]
        Resolve --> PT2["Twin sibling 2\n(released independently)"]

        Note2["3 candidates waiting on the\nIDENTICAL message at once —\nmessageEventReceived(name, execId)\ntargets exactly one"]
    end

    style Note1 fill:#e6f4ea,stroke:#2f855a
    style Note2 fill:#fdf3e7,stroke:#c05621
```
