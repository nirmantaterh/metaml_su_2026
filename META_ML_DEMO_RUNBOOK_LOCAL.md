# MetaML Executable Demo Runbook

Replace <repo-root> with the checkout directory. This is a live 5–10 minute demo using PowerShell and a browser. RedCollar is the primary scenario; VendorOnboarding is used to show direct Java delegate generation.

## A. Full executable demo flow

### 1. Start RabbitMQ

**ACTION:** Open PowerShell.

**COMMAND / CLICK:**
~~~powershell
docker run -d --name metaml-rabbitmq -p 5672:5672 -p 15672:15672 rabbitmq:3-management
docker exec metaml-rabbitmq rabbitmq-diagnostics -q ping
~~~
If it already exists:
~~~powershell
docker start metaml-rabbitmq
docker exec metaml-rabbitmq rabbitmq-diagnostics -q ping
~~~

**EXPECTED:** The ping succeeds. **SAY:** “RabbitMQ is the real transport between generated runtime components.” **PROVES:** Live messaging. **IF IT FAILS:** Run docker ps -a --filter name=metaml-rabbitmq and start the existing container.

### 2. Build/install backend and Workbench

**ACTION:** Open a second PowerShell terminal.

**COMMAND / CLICK:**
~~~powershell
cd <repo-root>\backend\workbench
mvn install -DskipTests
~~~

**EXPECTED:** BUILD SUCCESS. **SAY:** “Generate uses the current Workbench and generator build.” **PROVES:** Current source-of-truth build. **IF IT FAILS:** Confirm java -version, mvn -version, and the working directory.

### 3. Start wbapi

**ACTION:** Open a third terminal and leave it running.

**COMMAND / CLICK:**
~~~powershell
cd <repo-root>\backend\wbapi
mvn spring-boot:run
~~~

**EXPECTED:** API on port 8082. **SAY:** “The API owns project, generation, and launch orchestration.” **PROVES:** Workbench backend availability. **IF IT FAILS:** Check Get-NetTCPConnection -LocalPort 8082 -ErrorAction SilentlyContinue.

### 4. Start the frontend

**ACTION:** Open a fourth terminal and start the UI.

**COMMAND / CLICK:**
~~~powershell
cd <repo-root>\frontend
npm start
~~~
Open http://localhost:3000.

**EXPECTED:** Workbench loads. **SAY:** “This is the authoring surface for the executable platform.” **PROVES:** Browser-to-API Workbench connection. **IF IT FAILS:** Confirm wbapi is running and reload the URL.

### 5. Create/open a project

**ACTION:** Browser: project/workspace area → existing demo project, or Create Project → enter a name → Open.

**EXPECTED:** Project workspace opens. **SAY:** “The rest of the flow is model-driven from this project.” **PROVES:** Workbench project entry point. **IF IT FAILS:** Reload after confirming wbapi startup.

### 6. Import/open BPMN

**ACTION:** Click Import BPMN or Open Model → select RedCollar BPMN → confirm. Later repeat with VendorOnboarding.

**EXPECTED:** Canvas shows service tasks, user tasks, gateways, and the quality/rework loop. **SAY:** “BPMN defines the business path; MetaML does not manually jump branches.” **PROVES:** BPMN import/modeling. **IF IT FAILS:** Retry with the BPMN file, not an image/export.

### 7. Save

**ACTION:** Click Save or Save Model and wait for success.

**EXPECTED:** Saved model and enabled Generate action. **SAY:** “Saving gives generation a reproducible process source.” **PROVES:** Explicit model lifecycle. **IF IT FAILS:** Confirm a project is open and wbapi remains on 8082.

### 8. Generate

**ACTION:** Click Generate → confirm → wait for success.

**EXPECTED:** Fresh output under generated-target-platforms. **SAY:** “Generation creates reusable Original/Proxy and Twin runtime source.” **PROVES:** BPMN-to-Target-Platform generation. **IF IT FAILS:** Check the generation result and wbapi terminal, then save and retry once.

### 9. Locate the fresh generated project

**ACTION:** Run immediately after Generate.

**COMMAND / CLICK:**
~~~powershell
$dir = Get-ChildItem "<repo-root>\generated-target-platforms" -Directory |
  Sort-Object LastWriteTime -Descending |
  Select-Object -First 1 -ExpandProperty FullName
$dir
Test-Path "$dir\pom.xml"
~~~

**EXPECTED:** The newest project path is printed and Test-Path prints True. **SAY:** “The generated platform is an inspectable Maven project.” **PROVES:** Complete generated artifact. **IF IT FAILS:** List recent directories with Get-ChildItem "<repo-root>\generated-target-platforms" -Directory | Sort-Object LastWriteTime -Descending.

### 10. Inspect delegates/workers

**ACTION:** List each generated side.

**COMMAND / CLICK:**
~~~powershell
Get-ChildItem "$dir\src\main\java" -Recurse -File | Where-Object { $_.FullName -match '\\proxy\\delegates\\' } | Select-Object FullName
Get-ChildItem "$dir\src\main\java" -Recurse -File | Where-Object { $_.FullName -match '\\proxy\\worker\\' } | Select-Object FullName
Get-ChildItem "$dir\src\main\java" -Recurse -File | Where-Object { $_.FullName -match '\\twin\\delegates\\' } | Select-Object FullName
Get-ChildItem "$dir\src\main\java" -Recurse -File | Where-Object { $_.FullName -match '\\twin\\worker\\' } | Select-Object FullName
~~~

**EXPECTED:** RedCollar primarily proves external-task worker generation: proxy/worker and twin/worker are the important locations. Their delegate directories may be empty or sparse depending on the BPMN implementation types. For VendorOnboarding, proxy/delegates and twin/delegates are the important locations for direct Java delegate generation. **SAY:** “External service task means worker/. Java class or delegateExpression means delegates/. A process only contains the folders and classes its BPMN implementation types require.” **PROVES:** Generated Original/Proxy and Twin source and generic generation. **IF IT FAILS:** Run Get-ChildItem "$dir\src\main\java" -Recurse -File to confirm the generated package exists, then inspect the relevant worker or delegate location for the selected BPMN.

Use RedCollar to show worker generation; use VendorOnboarding to show direct delegate generation.

### 11. Launch from Workbench

**ACTION:** Browser: generated project launch page → Launch or Launch Target Platform → wait until ready.

**EXPECTED:** Launch row reports a dynamic port; Workbench-launched Target Platforms do not assume 8080. **SAY:** “The launcher records the actual port; 8080 is not a Workbench launch contract.” **PROVES:** Lifecycle ordering and dynamic launch integration. **IF IT FAILS:** Wait for readiness and inspect wbapi output.

### 12. Verify launch row and links

**ACTION:** Verify process, business key, port, and Target Platform URL → click Open Cockpit to open the Camunda Cockpit → return to the launch row → click Go to Target Platform to open the MetaML generated Target Platform portal.

**EXPECTED:** Open Cockpit opens Camunda Cockpit. Go to Target Platform opens the MetaML generated Target Platform portal. **SAY:** “The Workbench keeps the Camunda Cockpit and MetaML Target Platform portal as separate operator destinations.” **PROVES:** targetPlatformUrl propagation and separate navigation paths. **IF IT FAILS:** Refresh after readiness and wait for the URL field.

### 13. Open the Target Platform

**ACTION:** Use Go to Target Platform. This opens the MetaML generated Target Platform portal. Click Overview, Live Execution, Communication, Runtime, and System.

**EXPECTED:** All five MetaML portal pages load. **SAY:** “This is the MetaML generated Target Platform portal, separate from the Camunda Cockpit opened by Open Cockpit.” **PROVES:** The portal is part of the generated deliverable. **IF IT FAILS:** Confirm the URL uses the dynamic launch port.

### 14. Open Cockpit

**ACTION:** Click Open Cockpit from the Workbench row to open the Camunda Cockpit. Return to the generated Target Platform portal with Go to Target Platform when you need MetaML portal views.

**EXPECTED:** Camunda Cockpit opens as a separate application/view. The MetaML portal’s Overview page is reached through Go to Target Platform, not Open Cockpit. **SAY:** “Open Cockpit is the Camunda Cockpit handoff; Go to Target Platform is the MetaML portal handoff.” **PROVES:** Separate Workbench navigation destinations. **IF IT FAILS:** Use the corresponding link again and verify that Open Cockpit and the Target Platform URL are not being conflated.

### 15. Verify Original + Twin

**ACTION:** Live Execution → select current process/run pair → inspect Original/Proxy, Twin, and Synchronization panels.

**EXPECTED:** Both sides show the same progression and synchronized states. **SAY:** “The Twin is a live synchronized execution, not a duplicated screenshot.” **PROVES:** Original/Twin lockstep. **IF IT FAILS:** Select the current run pair and refresh once.

### 16. Verify RabbitMQ during runtime

**ACTION:** Keep Communication open; optionally open http://localhost:15672.

**COMMAND / CLICK:**
~~~powershell
docker ps --filter name=metaml-rabbitmq
docker exec metaml-rabbitmq rabbitmq-diagnostics -q ping
docker exec metaml-rabbitmq rabbitmqctl list_queues name messages consumers
~~~

**EXPECTED:** Container is running; portal shows events, requests, responses, and synchronization messages. **SAY:** “These events are moving through RabbitMQ as the generated runtimes execute.” **PROVES:** Real RabbitMQ communication. **IF IT FAILS:** Run docker logs --tail 50 metaml-rabbitmq and check System for RabbitMQ connected.

### 17. Verify provider execution

**ACTION:** System → show Available Providers. Then Live Execution or selected activity details → show Capability Providers Used for the selected run.

**EXPECTED:** Available Providers is the platform registry; Capability Providers Used lists only providers used by that run. **SAY:** “The dispatcher resolves, executes, validates, and fails closed when it cannot safely resolve a capability.” **PROVES:** Provider binding, ComponentExecutor/CapabilityDispatcher, output validation, fail-closed behavior, and provider tracking. **IF IT FAILS:** Check the selected run and provider status; do not treat unresolved capability as success.

### 18. Scenario 1 — Happy Path

**ACTION:** Live Execution → choose RedCollar Happy Path → Start New Run → select the new pair. Confirm the scenario configuration is applied while the run is held, then use Go to Next Step for gated activities or Complete Process when offered → refresh after transitions.

**EXPECTED:** order-approval = true, quality-check = true, straight-through path, and both sides = Completed. **SAY:** “True provider outputs let BPMN gateways choose the straight-through path.” **PROVES:** Normal capability execution, gateway routing, run controls, and synchronized completion. **IF IT FAILS:** Confirm the new run is selected and inspect its current activity and Communication messages.

### 19. Scenario 2 — Order Requires Editing

**ACTION:** Choose Order Requires Editing → Start New Run → select the new pair and confirm the scenario configuration is applied while the run is held → Go to Next Step to first order-approval → confirm false → advance and confirm the existing BPMN routes to Edit Order Details → open activity details and confirm that activity genuinely executes → Communication → point to its Original/Twin event → continue to second verification → confirm next order-approval = true → Complete Process when offered.

**EXPECTED:** orderApproved=false causes the existing BPMN to route to Edit Order Details; that activity genuinely executes; verification occurs again; the next order-approval returns true; both sides complete. **SAY:** “A provider result caused BPMN to route to the existing activity; the portal did not jump the branch.” **PROVES:** Real gateway routing, real activity execution, FIFO run-scoped responses, and synchronized continuation. **IF IT FAILS:** In Communication for the selected run, verify the Edit Order Details event exists on both sides before advancing.

### 20. Scenario 3 — Quality/Rework Until Resolved

**ACTION:** Choose Quality/Rework Until Resolved → Start New Run → select the new pair and confirm the scenario configuration is applied while the run is held → Go to Next Step to quality-check → confirm false and first rework → continue through loop → confirm false again and re-entry → timeline or Communication → verify a second rework event → continue until quality-check = true → confirm loop exit → Complete Process when offered.

**EXPECTED:** false → rework → false → rework → true → exit → completion; both sides = Completed. **SAY:** “This is an actual BPMN rework loop; responder subscription identity keeps repeated signal visits isolated.” **PROVES:** Repeated loop execution, FIFO run-scoped responses, branch-safe synchronization, and completion. **IF IT FAILS:** Use Communication to verify the second quality-check and second rework events.

**PRESENTER RULE:** “We are not manually selecting BPMN paths. We configure real provider outputs, those outputs pass through MetaML's capability runtime, and the BPMN gateways determine the execution path normally.”

### 21. Show Communication proof

**ACTION:** Communication → select a completed scenario run → point to Original/Proxy events, Twin events, and latest message stream.

**EXPECTED:** Approval, edit, quality, rework, request/response, and synchronization messages are visible. **SAY:** “This is message-level evidence for the synchronized execution.” **PROVES:** RabbitMQ and Original/Twin synchronization. **IF IT FAILS:** Run docker exec metaml-rabbitmq rabbitmqctl list_queues name messages consumers and confirm the selected run.

### 22. Show both sides complete

**ACTION:** Live Execution → select completed pair → inspect both badges and Synchronization → optionally check Overview.

**EXPECTED:** Original/Proxy = Completed, Twin = Completed, synchronization complete, and no unexpected incident. **SAY:** “Completion is verified on both generated sides.” **PROVES:** End-to-end synchronized completion. **IF IT FAILS:** Refresh and inspect the last pending activity in Communication.

### 23. Genericity with VendorOnboarding

**ACTION:** Workbench → import/open VendorOnboarding → Save → Generate → run:

~~~powershell
$dir = Get-ChildItem "<repo-root>\generated-target-platforms" -Directory |
  Sort-Object LastWriteTime -Descending |
  Select-Object -First 1 -ExpandProperty FullName
$dir
Test-Path "$dir\pom.xml"
Get-ChildItem "$dir\src\main\java" -Recurse -File | Where-Object { $_.FullName -match '\\delegates\\' } | Select-Object FullName
~~~

**EXPECTED:** Delegate classes appear under Original/Proxy and Twin delegates. No three-scenario run is required. **SAY:** “RedCollar shows worker generation; VendorOnboarding shows direct Java delegate generation, so MetaML is not RedCollar-only.” **PROVES:** Generic model-to-runtime generation. **IF IT FAILS:** Confirm $dir is the newest VendorOnboarding output and list all Java files recursively.

### 24. Optional unseen BPMN proof

**ACTION:** If asked, import a small unseen BPMN with one service task and one gateway → Save → Generate → repeat the $dir and artifact commands from steps 9–10.

**EXPECTED:** Corresponding runtime structure is generated. **SAY:** “The reusable mechanism is the generated runtime and capability contract.” **PROVES:** Genericity beyond the prepared demo. **IF IT FAILS:** Return to RedCollar; do not improvise an unsupported runtime scenario.

## B. Teammate speaking roles

- Workbench presenter: steps 5–8 and 23.
- Runtime presenter: steps 9–17 and 21–22.
- Scenario presenter: steps 18–20.
- Closer: genericity and plugin positioning.

The VS Code AI plugin fits conceptually upstream as an authoring/workflow assistant. Plugin integration is a separate follow-up workstream and is not required for this runtime demo.

## C. Generated artifact explanation

~~~text
generated-target-platforms/
└── <generated-project>/
    └── src/main/java/<generated-package>/
        ├── proxy/
        │   ├── delegates/
        │   └── worker/
        └── twin/
            ├── delegates/
            └── worker/
~~~

External service task → worker/  
Java class or delegateExpression → delegates/

Names and packages are dynamic; always locate the newest $dir.

## D. Three-scenario summary

| Scenario | Provider outputs | BPMN result |
|---|---|---|
| Happy Path | order-approval=true, quality-check=true | Straight-through completion |
| Order Requires Editing | order-approval=false, then true | Edit task executes, then continuation |
| Quality/Rework Until Resolved | quality-check=false, false, then true | Rework repeats, exits, and completes |

Provider outputs drive gateways. MetaML does not manually select or fabricate BPMN branches.

## E. Troubleshooting commands

~~~powershell
docker ps --filter name=metaml-rabbitmq
Get-NetTCPConnection -LocalPort 8082,3000 -ErrorAction SilentlyContinue
docker logs --tail 50 metaml-rabbitmq
Get-ChildItem "<repo-root>\generated-target-platforms" -Directory | Sort-Object LastWriteTime -Descending | Select-Object -First 5 Name,LastWriteTime
$dir = Get-ChildItem "<repo-root>\generated-target-platforms" -Directory | Sort-Object LastWriteTime -Descending | Select-Object -First 1 -ExpandProperty FullName
Test-Path "$dir\pom.xml"
~~~

Use the dynamic Target Platform URL from the launch row. For missing providers, inspect the selected run and preserve fail-closed behavior. For a stuck scenario, verify the selected run/current activity and use Go to Next Step. For synchronization concerns, inspect the selected run’s Communication events on both sides.

## F. Shutdown

**ACTION:** Stop each process from its terminal.

**COMMAND / CLICK:**
~~~text
Frontend terminal: Ctrl+C
wbapi terminal: Ctrl+C
Generated Target Platform terminal, if manually running: Ctrl+C
~~~

Optionally:
~~~powershell
docker stop metaml-rabbitmq
~~~

**EXPECTED:** Ports 3000, 8082, and the generated platform’s dynamic port are released. **SAY:** “The components are independently stoppable, and generated platforms do not require port 8080.” **PROVES:** Clean operational ownership. **IF IT FAILS:** Run Get-NetTCPConnection -LocalPort 3000,8082 -ErrorAction SilentlyContinue to identify remaining listeners.

## G. 30-second closing statement

“MetaML takes BPMN from the Workbench to a generated Target Platform with Original and Twin runtimes, real RabbitMQ communication, synchronized execution, and safely resolved capabilities. The three scenarios prove natural BPMN routing for straight-through work, human editing, and repeated rework. RedCollar is the demo process; the generated infrastructure and capability contracts are reusable. VS Code AI plugin integration is a separate follow-up workstream.”
