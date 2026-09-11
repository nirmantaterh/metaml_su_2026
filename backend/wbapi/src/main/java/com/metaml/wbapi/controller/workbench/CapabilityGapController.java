package com.metaml.wbapi.controller.workbench;

import com.metaml.wbapi.payload.request.CapabilityGapBindRequest;
import com.metaml.wbapi.payload.request.CapabilityGapReportRequest;
import com.metaml.wbapi.payload.response.ApiResponse;
import com.metaml.wbapi.utils.FeedbackMessage;
import com.metaml.wbapi.utils.WorkbenchUrlMapping;

import com.metaml.workbench.capability.CapabilityContract;
import com.metaml.workbench.capability.ExecutionMode;
import com.metaml.workbench.capability.IoDeclaration;
import com.metaml.workbench.capability.IoType;
import com.metaml.workbench.capability.gap.CapabilityGap;
import com.metaml.workbench.capability.gap.CapabilityGapIdentity;
import com.metaml.workbench.capability.gap.CapabilityGapRecommendation;
import com.metaml.workbench.capability.gap.CapabilityGapService;
import com.metaml.workbench.capability.gap.GapOrigin;
import com.metaml.workbench.capability.gap.GapStatus;
import com.metaml.workbench.model.AgentDecision;

import lombok.RequiredArgsConstructor;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR;
import static org.springframework.http.HttpStatus.NOT_FOUND;

// MetaML Scope 6, Phase 5: the Workbench-side HTTP boundary for the capability-gap lifecycle.
//
// /report is the one generic entry point every origin funnels through (section 12), including the
// "Target Platform -> HTTP POST -> Workbench gap endpoint" boundary (section 19) - this is that
// endpoint. Workbench remains authoritative: this controller only ever calls the existing
// CapabilityGapService, never a second governance/approval/evolution/catalog mechanism. Wiring a
// generated Target Platform to actually call this endpoint is deferred (see the P5 final report's
// "Target Platform" section) - RabbitMQ and Proxy/Twin synchronization stay out of scope.
@RestController
@RequestMapping(WorkbenchUrlMapping.CAPABILITY_GAPS)
@RequiredArgsConstructor
public class CapabilityGapController {

    private final CapabilityGapService capabilityGapService;

    @PostMapping(WorkbenchUrlMapping.CAPABILITY_GAP_REPORT)
    public ResponseEntity<ApiResponse> report(@RequestBody CapabilityGapReportRequest request) {
        try {
            CapabilityGap candidate = toCandidate(request);
            CapabilityGap reported = capabilityGapService.report(candidate);
            return ResponseEntity.ok(new ApiResponse(FeedbackMessage.SUCCESS, reported));
        } catch (IllegalArgumentException | NullPointerException e) {
            return ResponseEntity.status(BAD_REQUEST).body(new ApiResponse(e.getMessage(), null));
        } catch (Exception e) {
            return ResponseEntity.status(INTERNAL_SERVER_ERROR).body(new ApiResponse(e.getMessage(), null));
        }
    }

    @GetMapping
    public ResponseEntity<ApiResponse> list(@RequestParam(required = false) String tenantId) {
        try {
            return ResponseEntity.ok(new ApiResponse(FeedbackMessage.SUCCESS,
                    tenantId == null ? capabilityGapService.list() : capabilityGapService.listForTenant(tenantId)));
        } catch (Exception e) {
            return ResponseEntity.status(INTERNAL_SERVER_ERROR).body(new ApiResponse(e.getMessage(), null));
        }
    }

    @GetMapping("/{gapId}")
    public ResponseEntity<ApiResponse> get(@PathVariable String gapId) {
        try {
            return ResponseEntity.ok(new ApiResponse(FeedbackMessage.SUCCESS, capabilityGapService.get(gapId)));
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(NOT_FOUND).body(new ApiResponse(e.getMessage(), null));
        } catch (Exception e) {
            return ResponseEntity.status(INTERNAL_SERVER_ERROR).body(new ApiResponse(e.getMessage(), null));
        }
    }

    @PostMapping(WorkbenchUrlMapping.CAPABILITY_GAP_RECOMMEND)
    public ResponseEntity<ApiResponse> recommend(@PathVariable String gapId) {
        try {
            CapabilityGapRecommendation recommendation = capabilityGapService.recommend(gapId);
            return ResponseEntity.ok(new ApiResponse(FeedbackMessage.SUCCESS, recommendation));
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(NOT_FOUND).body(new ApiResponse(e.getMessage(), null));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(BAD_REQUEST).body(new ApiResponse(e.getMessage(), null));
        } catch (Exception e) {
            return ResponseEntity.status(INTERNAL_SERVER_ERROR).body(new ApiResponse(e.getMessage(), null));
        }
    }

    @PostMapping(WorkbenchUrlMapping.CAPABILITY_GAP_APPROVE)
    public ResponseEntity<ApiResponse> approve(@PathVariable String gapId, @RequestParam String tenantId) {
        try {
            AgentDecision decision = capabilityGapService.approve(gapId, tenantId);
            return ResponseEntity.ok(new ApiResponse(FeedbackMessage.SUCCESS, decision));
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(NOT_FOUND).body(new ApiResponse(e.getMessage(), null));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(BAD_REQUEST).body(new ApiResponse(e.getMessage(), null));
        } catch (Exception e) {
            return ResponseEntity.status(INTERNAL_SERVER_ERROR).body(new ApiResponse(e.getMessage(), null));
        }
    }

    @PostMapping(WorkbenchUrlMapping.CAPABILITY_GAP_REJECT)
    public ResponseEntity<ApiResponse> reject(@PathVariable String gapId,
            @RequestParam(required = false) String reason) {
        try {
            capabilityGapService.reject(gapId, reason == null ? "rejected via API" : reason);
            return ResponseEntity.ok(new ApiResponse(FeedbackMessage.SUCCESS, capabilityGapService.get(gapId)));
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(NOT_FOUND).body(new ApiResponse(e.getMessage(), null));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(BAD_REQUEST).body(new ApiResponse(e.getMessage(), null));
        } catch (Exception e) {
            return ResponseEntity.status(INTERNAL_SERVER_ERROR).body(new ApiResponse(e.getMessage(), null));
        }
    }

    // MetaML Scope 6, Phase 6: an optional request body may carry confirmedProviderId - an
    // already AI-assisted, human-confirmed provider id from the existing VS Code AiDecisionProvider
    // path (section 4). CapabilityGapService.bind(gapId, confirmedProviderId) independently
    // re-validates it against the live catalog and CapabilitySatisfaction before ever binding
    // (section 7); omitting the body preserves the original deterministic bind(gapId) behavior
    // unchanged for every existing caller.
    @PostMapping(WorkbenchUrlMapping.CAPABILITY_GAP_BIND)
    public ResponseEntity<ApiResponse> bind(@PathVariable String gapId,
            @RequestBody(required = false) CapabilityGapBindRequest request) {
        try {
            String confirmedProviderId = request == null ? null : request.getConfirmedProviderId();
            AgentDecision decision = capabilityGapService.bind(gapId, confirmedProviderId);
            return ResponseEntity.ok(new ApiResponse(FeedbackMessage.SUCCESS, decision));
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(NOT_FOUND).body(new ApiResponse(e.getMessage(), null));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(BAD_REQUEST).body(new ApiResponse(e.getMessage(), null));
        } catch (Exception e) {
            return ResponseEntity.status(INTERNAL_SERVER_ERROR).body(new ApiResponse(e.getMessage(), null));
        }
    }

    // Builds the OPEN candidate CapabilityGap the report describes; CapabilityGapService.report(...)
    // decides idempotently whether this is a new gap or a refresh of an existing one (section 11).
    private static CapabilityGap toCandidate(CapabilityGapReportRequest request) {
        if (request.getProcessDefinitionId() == null || request.getProcessDefinitionId().isBlank()) {
            throw new IllegalArgumentException("processDefinitionId must not be blank");
        }
        if (request.getActivityId() == null || request.getActivityId().isBlank()) {
            throw new IllegalArgumentException("activityId must not be blank");
        }
        if (request.getOrigin() == null) {
            throw new IllegalArgumentException("origin must not be blank");
        }

        Set<IoDeclaration> requiredInputs = toDeclarations(request.getRequiredInputs(), false);
        Set<IoDeclaration> requiredOutputs = toDeclarations(request.getRequiredOutputs(), true);
        CapabilityContract contract = new CapabilityContract(request.getCapabilityId(), requiredInputs,
                requiredOutputs, ExecutionMode.SYNCHRONOUS, Map.of(),
                request.getGovernanceLabels() == null ? Set.of() : Set.copyOf(request.getGovernanceLabels()));

        Map<String, IoType> availableInputs = new LinkedHashMap<>();
        if (request.getAvailableInputs() != null) {
            request.getAvailableInputs().forEach((name, type) -> availableInputs.put(name, IoType.valueOf(type)));
        }

        String gapId = CapabilityGapIdentity.gapId(request.getProcessDefinitionId(), request.getActivityId(),
                request.getActivityInstanceId(), requiredOutputs.stream().map(IoDeclaration::name)
                        .collect(java.util.stream.Collectors.toSet()));

        Instant now = Instant.now();
        // candidateProviderIds is intentionally empty here: this controller has no catalog access
        // and must not calculate capability-satisfaction domain semantics itself (it would be a
        // second matching algorithm, disagreeing with CapabilitySatisfaction by construction).
        // CapabilityGapService.report(...) - the sole authoritative entry point this candidate is
        // about to be passed to - recomputes the real candidate set against the live catalog before
        // ever storing or returning the gap, so this placeholder never reaches an API response.
        return new CapabilityGap(gapId, request.getProcessDefinitionId(), request.getActivityId(),
                request.getActivityInstanceId(), request.getLoopCounter(), request.getProcessInstanceId(), contract,
                availableInputs, java.util.List.of(), request.getTenantId(), GapOrigin.valueOf(request.getOrigin()),
                GapStatus.OPEN, now, now, null, null);
    }

    private static Set<IoDeclaration> toDeclarations(Map<String, String> byNameType, boolean required) {
        Set<IoDeclaration> declarations = new LinkedHashSet<>();
        if (byNameType != null) {
            byNameType.forEach((name, type) -> declarations.add(new IoDeclaration(name, IoType.valueOf(type),
                    required)));
        }
        return declarations;
    }
}
