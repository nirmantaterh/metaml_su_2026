package com.metaml.wbapi;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.metaml.wbapi.controller.workbench.WorkbenchController;
import com.metaml.wbapi.payload.request.EvolveActivityRequest;
import com.metaml.wbapi.payload.response.ApiResponse;
import com.metaml.workbench.model.AgentDecision;
import com.metaml.workbench.service.WorkbenchService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

// Scope 6 identity fix, controller layer: proves WorkbenchController#evolveActivity routes to the
// 4-argument WorkbenchService#evolveActivity overload exactly when the request carries a non-blank
// activityInstanceId, and to the existing 3-argument overload otherwise - never both, never the
// wrong one. Pure Mockito unit test against the controller directly (no Spring context needed),
// same pattern as TransmuteAgentsEndpointTest.
class EvolveActivityInstanceRoutingTest {

    private static final String TWIN_ID = "twin-1";
    private static final String ACTIVITY_ID = "Task_X";
    private static final String INSTANCE_ID = "activity-instance-42";
    private static final String AGENT_TYPE = "validator";

    private final WorkbenchService service = mock(WorkbenchService.class);
    private final WorkbenchController controller = new WorkbenchController(service);

    // TEST E: activityInstanceId present -> 4-argument overload reached, 3-argument overload
    // never called.
    @Test
    void requestWithActivityInstanceIdRoutesToFourArgumentOverload() {
        EvolveActivityRequest request = new EvolveActivityRequest();
        request.setTwinProcessId(TWIN_ID);
        request.setActivityId(ACTIVITY_ID);
        request.setAgentType(AGENT_TYPE);
        request.setActivityInstanceId(INSTANCE_ID);

        AgentDecision expected = new AgentDecision(AGENT_TYPE, true, "validator-agent-01", "Available");
        given(service.evolveActivity(TWIN_ID, ACTIVITY_ID, INSTANCE_ID, AGENT_TYPE)).willReturn(expected);

        ResponseEntity<ApiResponse> response = controller.evolveActivity(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getData()).isEqualTo(expected);
        verify(service).evolveActivity(TWIN_ID, ACTIVITY_ID, INSTANCE_ID, AGENT_TYPE);
        verify(service, never()).evolveActivity(eq(TWIN_ID), eq(ACTIVITY_ID), eq(AGENT_TYPE));
    }

    // TEST F: activityInstanceId absent -> existing 3-argument overload reached unchanged,
    // 4-argument overload never called. Preserves legacy request compatibility.
    @Test
    void requestWithoutActivityInstanceIdRoutesToThreeArgumentOverload() {
        EvolveActivityRequest request = new EvolveActivityRequest();
        request.setTwinProcessId(TWIN_ID);
        request.setActivityId(ACTIVITY_ID);
        request.setAgentType(AGENT_TYPE);
        // activityInstanceId left null - exactly what a legacy/unmodified client sends.

        AgentDecision expected = new AgentDecision(AGENT_TYPE, true, "validator-agent-01", "Available");
        given(service.evolveActivity(TWIN_ID, ACTIVITY_ID, AGENT_TYPE)).willReturn(expected);

        ResponseEntity<ApiResponse> response = controller.evolveActivity(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getData()).isEqualTo(expected);
        verify(service).evolveActivity(TWIN_ID, ACTIVITY_ID, AGENT_TYPE);
        verify(service, never()).evolveActivity(eq(TWIN_ID), eq(ACTIVITY_ID), isNull(), eq(AGENT_TYPE));
    }

    // Blank (not just null) activityInstanceId must also fall back to the legacy overload -
    // proves the controller checks for blank, not merely non-null.
    @Test
    void requestWithBlankActivityInstanceIdRoutesToThreeArgumentOverload() {
        EvolveActivityRequest request = new EvolveActivityRequest();
        request.setTwinProcessId(TWIN_ID);
        request.setActivityId(ACTIVITY_ID);
        request.setAgentType(AGENT_TYPE);
        request.setActivityInstanceId("   ");

        AgentDecision expected = new AgentDecision(AGENT_TYPE, true, "validator-agent-01", "Available");
        given(service.evolveActivity(TWIN_ID, ACTIVITY_ID, AGENT_TYPE)).willReturn(expected);

        ResponseEntity<ApiResponse> response = controller.evolveActivity(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(service).evolveActivity(TWIN_ID, ACTIVITY_ID, AGENT_TYPE);
        verify(service, never()).evolveActivity(eq(TWIN_ID), eq(ACTIVITY_ID), eq("   "), eq(AGENT_TYPE));
    }
}
