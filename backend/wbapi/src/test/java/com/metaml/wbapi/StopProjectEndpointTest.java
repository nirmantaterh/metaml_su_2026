package com.metaml.wbapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.metaml.wbapi.controller.workbench.WorkbenchController;
import com.metaml.wbapi.payload.request.StopProjectRequest;
import com.metaml.wbapi.payload.response.ApiResponse;
import com.metaml.workbench.service.WorkbenchService;

class StopProjectEndpointTest {

    private final WorkbenchService service = mock(WorkbenchService.class);
    private final WorkbenchController controller = new WorkbenchController(service);

    @Test
    void stoppingSomethingThatWasRunningIs200WithWasRunningTrue() {
        when(service.stopGeneratedProject("p1")).thenReturn(true);

        ResponseEntity<ApiResponse> response = controller.stopGeneratedProject(new StopProjectRequest("p1"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().getData()).isEqualTo(true);
    }

    // matches how every other "you named something that isn't there" case in this controller
    // answers, rather than a 200 the caller has to read the body to interpret
    @Test
    void stoppingSomethingThatWasNotRunningIs404() {
        when(service.stopGeneratedProject("gone")).thenReturn(false);

        ResponseEntity<ApiResponse> response = controller.stopGeneratedProject(new StopProjectRequest("gone"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().getData()).isEqualTo(false);
        assertThat(response.getBody().getMessage()).contains("gone");
    }

    @Test
    void aBlankProjectIdIs400NotA500() {
        when(service.stopGeneratedProject(anyString()))
                .thenThrow(new IllegalArgumentException("projectId must not be blank"));

        ResponseEntity<ApiResponse> response = controller.stopGeneratedProject(new StopProjectRequest(""));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
