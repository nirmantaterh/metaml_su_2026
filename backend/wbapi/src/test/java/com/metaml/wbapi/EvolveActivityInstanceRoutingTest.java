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

// Verifies that controller routes to 4-arg service overload when activityInstanceId is present.
class EvolveActivityInstanceRoutingTest {

    private static final String TWIN_ID = "twin-1";
    private static final String ACTIVITY_ID = "Task_X";
    private static final String INSTANCE_ID = "activity-instance-42";
    private static final String AGENT_TYPE = "validator";

    private final WorkbenchService service = mock(WorkbenchService.class);
    private final WorkbenchController controller = new WorkbenchController(service);

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

    @Test
    void requestWithoutActivityInstanceIdRoutesToThreeArgumentOverload() {
        EvolveActivityRequest request = new EvolveActivityRequest();
        request.setTwinProcessId(TWIN_ID);
        request.setActivityId(ACTIVITY_ID);
        request.setAgentType(AGENT_TYPE);

        AgentDecision expected = new AgentDecision(AGENT_TYPE, true, "validator-agent-01", "Available");
        given(service.evolveActivity(TWIN_ID, ACTIVITY_ID, AGENT_TYPE)).willReturn(expected);

        ResponseEntity<ApiResponse> response = controller.evolveActivity(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getData()).isEqualTo(expected);
        verify(service).evolveActivity(TWIN_ID, ACTIVITY_ID, AGENT_TYPE);
        verify(service, never()).evolveActivity(eq(TWIN_ID), eq(ACTIVITY_ID), isNull(), eq(AGENT_TYPE));
    }

    // Whitespace activityInstanceId is treated as omitted.
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
