package com.metaml.wbapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.metaml.wbapi.controller.workbench.CapabilityGapController;
import com.metaml.wbapi.payload.request.CapabilityGapReportRequest;
import com.metaml.wbapi.payload.response.ApiResponse;

import com.metaml.workbench.capability.CapabilityContract;
import com.metaml.workbench.capability.ExecutionMode;
import com.metaml.workbench.capability.IoDeclaration;
import com.metaml.workbench.capability.IoType;
import com.metaml.workbench.capability.gap.CapabilityGap;
import com.metaml.workbench.capability.gap.CapabilityGapService;
import com.metaml.workbench.capability.gap.GapOrigin;
import com.metaml.workbench.capability.gap.GapStatus;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

// MetaML Scope 6, Phase 6 correction: regression coverage for the /report HTTP boundary
// (CapabilityGapController.report -> toCandidate). Proves the controller serializes backend truth
// rather than erasing it - the exact defect this pass fixed (toCandidate() hardcoded
// candidateProviderIds = List.of() and, before CapabilityGapService.report() was corrected to
// recompute the authoritative candidate set, that empty placeholder was what got stored and
// returned). This test never re-implements capability satisfaction: it mocks CapabilityGapService
// itself, so it isolates "does the controller pass the service's answer through unmodified" from
// "does the service compute the right answer" (that half is CapabilityGapServiceTest's job).
class CapabilityGapControllerTest {

    private final CapabilityGapService service = mock(CapabilityGapService.class);
    private final CapabilityGapController controller = new CapabilityGapController(service);

    private static CapabilityGapReportRequest genericReportRequest() {
        CapabilityGapReportRequest request = new CapabilityGapReportRequest();
        request.setProcessDefinitionId("genericProcess");
        request.setActivityId("GenericActivity");
        request.setOrigin(GapOrigin.RUNTIME_TARGET_PLATFORM.name());
        request.setCapabilityId(null);
        request.setRequiredOutputs(Map.of("result", "UNKNOWN"));
        return request;
    }

    private static CapabilityGap gapWithCandidates(List<String> candidateProviderIds) {
        CapabilityContract contract = new CapabilityContract(null, Set.of(),
                Set.of(new IoDeclaration("result", IoType.UNKNOWN, true)), ExecutionMode.SYNCHRONOUS, Map.of(),
                Set.of());
        Instant now = Instant.now();
        return new CapabilityGap("gap-1", "genericProcess", "GenericActivity", null, null, null, contract, Map.of(),
                candidateProviderIds, null, GapOrigin.RUNTIME_TARGET_PLATFORM, GapStatus.OPEN, now, now, null, null);
    }

    // Test 5 (P6 required regression): the API representation must expose the same non-empty
    // candidate set CapabilityGapService.report(...) actually computed and returned - toCandidate()
    // building its own placeholder candidate must never survive into the HTTP response.
    @Test
    void reportResponseExposesTheNonEmptyCandidateSetTheServiceReturned() {
        CapabilityGap serviceAnswer = gapWithCandidates(List.of("provider-a", "provider-b"));
        given(service.report(any(CapabilityGap.class))).willReturn(serviceAnswer);

        ResponseEntity<ApiResponse> response = controller.report(genericReportRequest());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Object data = response.getBody().getData();
        assertThat(data).isInstanceOf(CapabilityGap.class);
        assertThat(((CapabilityGap) data).candidateProviderIds()).containsExactly("provider-a", "provider-b");
    }

    // The empty-candidate case must also pass through faithfully - a real NO_SUITABLE_PROVIDER gap,
    // not merely the controller's own placeholder happening to be empty too.
    @Test
    void reportResponseExposesAnEmptyCandidateSetWhenTheServiceGenuinelyFoundNone() {
        CapabilityGap serviceAnswer = gapWithCandidates(List.of());
        given(service.report(any(CapabilityGap.class))).willReturn(serviceAnswer);

        ResponseEntity<ApiResponse> response = controller.report(genericReportRequest());

        CapabilityGap data = (CapabilityGap) response.getBody().getData();
        assertThat(data.candidateProviderIds()).isEmpty();
    }

    // The controller itself must never compute or invent a candidate set - it hands
    // CapabilityGapService.report(...) a candidate whose own candidateProviderIds is the caller-blind
    // placeholder (empty), leaving candidate derivation entirely to the service/CapabilitySatisfaction.
    @Test
    void controllerNeverComputesCandidatesItselfBeforeDelegatingToTheService() {
        given(service.report(any(CapabilityGap.class))).willReturn(gapWithCandidates(List.of("provider-a")));

        controller.report(genericReportRequest());

        org.mockito.ArgumentCaptor<CapabilityGap> captor = org.mockito.ArgumentCaptor.forClass(CapabilityGap.class);
        org.mockito.Mockito.verify(service).report(captor.capture());
        assertThat(captor.getValue().candidateProviderIds())
                .as("the controller has no catalog access and must not calculate satisfaction itself")
                .isEmpty();
    }
}
