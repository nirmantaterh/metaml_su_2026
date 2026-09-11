package com.example.camundademo.capability;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.camunda.bpm.model.bpmn.Bpmn;
import org.camunda.bpm.model.bpmn.BpmnModelInstance;
import org.camunda.bpm.model.bpmn.instance.Activity;
import org.camunda.bpm.model.bpmn.instance.Process;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import com.metaml.workbench.capability.CapabilityContract;
import com.metaml.workbench.capability.ExecutionMode;
import com.metaml.workbench.capability.IoDeclaration;
import com.metaml.workbench.capability.IoType;
import com.metaml.workbench.capability.runtime.CapabilityBinding;
import com.metaml.workbench.capability.runtime.CapabilityBindingResolver;

// The Target Platform side of P7 Step 5's binding retrieval: an HTTP call against the Workbench's
// GET /transmute/bindings, addressed by BPMN process key and activity id - never by any
// Workbench-internal twin process/instance id, which this standalone application has no way to know.
//
// Registered ONLY when metaml.capability.workbench-url is configured - a Target Platform deployed
// with the Workbench unreachable (or deliberately switched off) simply never gets this bean, and
// CapabilityConfig's ObjectProvider<CapabilityBindingResolver> then resolves to null, which
// CapabilityBindingCache already treats as "nothing to fall back to" rather than an error.
//
// Bulk activity-id scoping is derived from this application's OWN bundled BPMN resources
// (src/main/resources/processes/*.bpmn, which every generated Target Platform already ships) rather
// than from any live Workbench call - this is a boot-time, read-only inspection of what this
// application itself is, not a retrieval. It deliberately does not try to distinguish which bundled
// process is the Twin (the only side capability dispatch ever runs on - see
// ExternalTaskWorkerGenerator.renderTwinWorkerSource) from the Proxy: every camunda:type="external"
// activity id across every bundled BPMN is offered to the boot-time bulk fetch. A Proxy-side id that
// happens to have no binding simply comes back absent, exactly like any other unbound activity -
// never fabricated, never an error - so this is a harmless minor over-fetch, not a correctness risk.
@Component
@ConditionalOnProperty("metaml.capability.workbench-url")
public class RestCapabilityBindingClient implements CapabilityBindingResolver {

    private static final Logger logger = LoggerFactory.getLogger(RestCapabilityBindingClient.class);
    private static final String CAMUNDA_NS = "http://camunda.org/schema/1.0/bpmn";
    private static final String BINDINGS_PATH = "/api/v1/wb/transmute/bindings";

    private final RestClient restClient;
    // Built once at construction from this application's own bundled BPMN - stable for the life of
    // the process, exactly like the BPMN resources themselves.
    private final Map<String, List<String>> activityIdsByProcessKey;
    private final Map<String, String> processKeyByActivityId;

    public RestCapabilityBindingClient(RestClient.Builder restClientBuilder,
            @Value("${metaml.capability.workbench-url}") String workbenchUrl) {
        this.restClient = restClientBuilder.baseUrl(workbenchUrl).build();
        Map<String, List<String>> byProcessKey = new LinkedHashMap<>();
        Map<String, String> byActivityId = new LinkedHashMap<>();
        for (BundledProcess process : bundledProcesses()) {
            byProcessKey.computeIfAbsent(process.processKey(), k -> new ArrayList<>()).addAll(process.activityIds());
            for (String activityId : process.activityIds()) {
                byActivityId.put(activityId, process.processKey());
            }
        }
        this.activityIdsByProcessKey = Map.copyOf(byProcessKey);
        this.processKeyByActivityId = Map.copyOf(byActivityId);
    }

    // What CapabilityBindingBootstrap fetches once at boot: every capability-relevant activity id
    // this application bundles, grouped by process key.
    public Map<String, List<String>> activityIdsByProcessKey() {
        return activityIdsByProcessKey;
    }

    public List<CapabilityBinding> resolveAll(String processKey, List<String> activityIds) {
        if (activityIds.isEmpty()) {
            return List.of();
        }
        try {
            String uri = UriComponentsBuilder.fromPath(BINDINGS_PATH).queryParam("processKey", processKey)
                    .queryParam("activityIds", String.join(",", activityIds)).toUriString();
            ApiResponseDto response = restClient.get().uri(uri).retrieve().body(ApiResponseDto.class);
            if (response == null || response.data == null) {
                return List.of();
            }
            List<CapabilityBinding> bindings = new ArrayList<>();
            for (BindingDto dto : response.data) {
                bindings.add(dto.toCapabilityBinding());
            }
            return bindings;
        } catch (RuntimeException e) {
            // Unreachable Workbench, non-2xx, malformed response - all the same "could not retrieve"
            // outcome to this seam's one caller (CapabilityBindingCache), which already treats a
            // resolver failure identically to "nothing resolved". Never thrown further.
            logger.warn("Could not retrieve capability bindings for process '{}': {}", processKey,
                    e.getMessage());
            return List.of();
        }
    }

    @Override
    public Optional<CapabilityBinding> resolve(String activityId) {
        String processKey = processKeyByActivityId.get(activityId);
        if (processKey == null) {
            return Optional.empty();
        }
        List<CapabilityBinding> bindings = resolveAll(processKey, List.of(activityId));
        return bindings.stream().filter(b -> b.activityId().equals(activityId)).findFirst();
    }

    private record BundledProcess(String processKey, List<String> activityIds) {
    }

    private List<BundledProcess> bundledProcesses() {
        List<BundledProcess> processes = new ArrayList<>();
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver()
                    .getResources("classpath:processes/*.bpmn");
            for (Resource resource : resources) {
                try (InputStream in = resource.getInputStream()) {
                    BpmnModelInstance model = Bpmn.readModelFromStream(in);
                    for (Process process : model.getModelElementsByType(Process.class)) {
                        Set<String> activityIds = new LinkedHashSet<>();
                        for (Activity activity : model.getModelElementsByType(Activity.class)) {
                            if ("external".equals(activity.getAttributeValueNs(CAMUNDA_NS, "type"))) {
                                activityIds.add(activity.getId());
                            }
                        }
                        if (!activityIds.isEmpty()) {
                            processes.add(new BundledProcess(process.getId(), List.copyOf(activityIds)));
                        }
                    }
                } catch (IOException | RuntimeException e) {
                    logger.warn("Could not read bundled process resource {}: {}", resource, e.getMessage());
                }
            }
        } catch (IOException e) {
            logger.warn("Could not enumerate bundled process resources: {}", e.getMessage());
        }
        return processes;
    }

    // ---- wire shapes, mirroring com.metaml.wbapi.payload.response.CapabilityBindingResponse ----

    static final class ApiResponseDto {
        public String message;
        public List<BindingDto> data;
    }

    static final class IoDeclarationDto {
        public String name;
        public String type;
        public boolean required;
    }

    static final class BindingDto {
        public String processDefinitionKey;
        public String activityId;
        public String providerId;
        public String providerType;
        public String version;
        public String capabilityId;
        public List<IoDeclarationDto> producedOutputs;
        public String approvalId;
        public Long boundAtEpochMillis;

        CapabilityBinding toCapabilityBinding() {
            Set<IoDeclaration> outputs = new LinkedHashSet<>();
            if (producedOutputs != null) {
                for (IoDeclarationDto dto : producedOutputs) {
                    outputs.add(new IoDeclaration(dto.name, IoType.valueOf(dto.type), dto.required));
                }
            }
            // requiredInputs/executionMode/governanceLabels/constraints are not carried on the wire -
            // see CapabilityBindingResponse's own documentation for why; safe, inert defaults here.
            CapabilityContract contract = new CapabilityContract(capabilityId, Set.of(), outputs,
                    ExecutionMode.SYNCHRONOUS, Map.of(), Set.of());
            return new CapabilityBinding(processDefinitionKey, activityId, providerId, providerType, version,
                    contract, approvalId, boundAtEpochMillis == null ? Instant.EPOCH
                            : Instant.ofEpochMilli(boundAtEpochMillis));
        }
    }
}
