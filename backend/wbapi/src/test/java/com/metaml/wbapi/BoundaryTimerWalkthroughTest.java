package com.metaml.wbapi;

import org.camunda.bpm.engine.ManagementService;
import org.camunda.bpm.engine.ProcessEngineConfiguration;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.TaskService;
import org.camunda.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.camunda.bpm.engine.impl.util.ClockUtil;
import org.camunda.bpm.engine.runtime.Job;
import org.camunda.bpm.engine.task.Task;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.metaml.workbench.client.AgentAvailabilityResult;
import com.metaml.workbench.client.NodeManagerClient;
import com.metaml.workbench.model.ProcessModel;
import com.metaml.workbench.model.TwinProcess;
import com.metaml.workbench.service.GovernanceService;
import com.metaml.workbench.service.WorkbenchService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Date;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

// Verifies that Camunda boundary timer events fire and advance the process when job execution is active.
@IsolatedWorkbenchTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:metaml-timer-test;DB_CLOSE_DELAY=-1",
        "camunda.bpm.job-execution.wait-time-in-millis=250",
        "camunda.bpm.job-execution.max-wait=1000"
})
class BoundaryTimerWalkthroughTest {

    private static final Logger logger = LoggerFactory.getLogger(BoundaryTimerWalkthroughTest.class);

    private static final String ESCALATE_TIMEOUT = "Task_EscalateTimeout";
    private static final Duration TIMER_POLL_LIMIT = Duration.ofSeconds(30);

    @MockitoBean
    private NodeManagerClient nodeManagerClient;

    @Autowired
    private WorkbenchService workbenchService;
    @Autowired
    private GovernanceService governanceService;
    @Autowired
    private RuntimeService runtimeService;
    @Autowired
    private TaskService taskService;
    @Autowired
    private ManagementService managementService;
    @Autowired
    private ProcessEngineConfiguration processEngineConfiguration;

    @BeforeEach
    void stubTheCatalogAndOpenBothQuotas() {
        given(nodeManagerClient.checkAgentAvailability(anyString())).willAnswer(call -> {
            String type = call.getArgument(0);
            return new AgentAvailabilityResult(type, true, type + "-agent-01", "stub catalog", false);
        });
        governanceService.updatePolicy(Set.of(), 20, 200);
    }

    @AfterEach
    void putTheClockBack() {
        ClockUtil.reset();
    }

    @Test
    void theJobExecutorRunsWithNothingInThePropertiesTellingItTo() {
        ProcessEngineConfigurationImpl configuration =
                (ProcessEngineConfigurationImpl) processEngineConfiguration;
        logger.info("Job executor active: {}", configuration.getJobExecutor().isActive());
        assertThat(configuration.getJobExecutor().isActive()).isTrue();
    }

    @Test
    void theWireTransferApprovalTimeoutStillFiresAfterEightHours() throws Exception {
        TwinProcess twin = launch("citi wire transfer boundary timer", "citibank-wire-transfer.bpmn");
        assertThat(workbenchService.completeCurrentTasks(twin.getId())).hasSize(1);
        assertThat(workbenchService.completeCurrentTasks(twin.getId())).hasSize(3);
        assertThat(openTasks(twin)).containsExactly("Task_Approve");

        assertThat(timerJobs(twin)).hasSize(1);
        logger.info("Approval timeout due at {}", timerJobs(twin).get(0).getDuedate());

        assertThat(fastForwardUntil(twin, ESCALATE_TIMEOUT, Duration.ofHours(9))).isTrue();
        assertThat(openTasks(twin)).containsExactly(ESCALATE_TIMEOUT);
    }

    @Test
    void theCommitteeReviewTimeoutStillFiresAfterFourHours() throws Exception {
        TwinProcess twin = launch("grad admission boundary timer", "grad-admission-review.bpmn");
        assertThat(workbenchService.completeCurrentTasks(twin.getId())).hasSize(1);
        assertThat(workbenchService.completeCurrentTasks(twin.getId())).hasSize(2);
        assertThat(openTasks(twin)).containsExactly("Task_CommitteeReview");

        assertThat(timerJobs(twin)).hasSize(1);
        logger.info("Committee timeout due at {}", timerJobs(twin).get(0).getDuedate());

        assertThat(fastForwardUntil(twin, ESCALATE_TIMEOUT, Duration.ofHours(5))).isTrue();
        assertThat(openTasks(twin)).containsExactly(ESCALATE_TIMEOUT);
    }

    @Test
    void aLiveTwinOwnsNoJobsAtAll() throws IOException {
        TwinProcess twin = launch("citi wire transfer twin has no jobs", "citibank-wire-transfer.bpmn");
        workbenchService.connectActivity(twin.getId(), "Task_KYC", "Task_KYC");
        assertThat(workbenchService.bridgeActivityEvent(twin.getId(), "Task_KYC").isApproved()).isTrue();

        assertThat(managementService.createJobQuery()
                .processInstanceId(twin.getTwinProcessId()).count()).isZero();
    }

    private TwinProcess launch(String modelName, String fileName) throws IOException {
        ProcessModel model = workbenchService.saveProcessModel(null, modelName, example(fileName));
        return workbenchService.launchProcess(model.getId());
    }

    // Advances ClockUtil and notifies JobExecutor acquisition.
    private boolean fastForwardUntil(TwinProcess twin, String activityId, Duration ahead)
            throws InterruptedException {
        ClockUtil.setCurrentTime(new Date(System.currentTimeMillis() + ahead.toMillis()));
        ((ProcessEngineConfigurationImpl) processEngineConfiguration).getJobExecutor().jobWasAdded();

        long deadline = System.currentTimeMillis() + TIMER_POLL_LIMIT.toMillis();
        while (System.currentTimeMillis() < deadline) {
            if (openTasks(twin).contains(activityId)) {
                return true;
            }
            Thread.sleep(200);
        }
        logger.warn("Timer never fired; the original was still on {}", openTasks(twin));
        return false;
    }

    private List<Job> timerJobs(TwinProcess twin) {
        return managementService.createJobQuery()
                .processInstanceId(twin.getOriginalProcessId())
                .timers()
                .list();
    }

    private List<String> openTasks(TwinProcess twin) {
        return taskService.createTaskQuery()
                .processInstanceId(twin.getOriginalProcessId())
                .list()
                .stream()
                .map(Task::getTaskDefinitionKey)
                .sorted()
                .toList();
    }

    private static String example(String fileName) throws IOException {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            Path candidate = dir.resolve("examples").resolve(fileName);
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate, StandardCharsets.UTF_8);
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("no examples/" + fileName + " above " + Path.of("").toAbsolutePath());
    }
}
