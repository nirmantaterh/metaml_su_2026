package com.tp.TargetPlatform.portal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

// Locks in the parsing this portal's Messages/Runtime/Communication views depend on: a TASK/RESPONSE
// log line's businessKey and processInstanceId fields must come back as the values the generated
// TaskQueuePublisher/TaskQueueListener/ResponseQueuePublisher/ResponseQueueListener classes actually
// wrote under those names - not the executionId that appears earlier in the same line. Every fixture
// line uses the exact wording TargetPlatformMessagingGenerator emits (see its own source), generic
// activity/signal names, no RedCollar or other domain vocabulary.
class RuntimeEventLogTest {

    @Test
    void aTaskPublishedLineYieldsTheNamedFieldsNotTheExecutionId() {
        String message = "TASK: published signal 'sync_StepOne' to RabbitMQ exchange 'flow.abc.exchange' "
                + "key 'sync.sync-step-one' for execution EXEC-1 (processInstanceId=PID-TWIN, "
                + "businessKey=run-42)";

        RuntimeEventLog.Entry entry = RuntimeEventLog.buildEntry(1, 1000L, "INFO", "com.tp.TargetPlatform.x",
                message);

        assertThat(entry.kind()).isEqualTo("TASK");
        assertThat(entry.direction()).isEqualTo("TASK");
        assertThat(entry.signal()).isEqualTo("sync_StepOne");
        assertThat(entry.processInstanceId()).isEqualTo("PID-TWIN");
        assertThat(entry.businessKey()).isEqualTo("run-42");
    }

    @Test
    void aResponseDeliveredLineYieldsTheNamedFieldsNotTheExecutionId() {
        String message = "RESPONSE: delivered signal 'sync_StepOne' to execution EXEC-2 "
                + "(processInstanceId=PID-ORIGINAL, businessKey=run-42) via RabbitMQ";

        RuntimeEventLog.Entry entry = RuntimeEventLog.buildEntry(2, 2000L, "INFO", "com.tp.TargetPlatform.x",
                message);

        assertThat(entry.kind()).isEqualTo("RESPONSE");
        assertThat(entry.direction()).isEqualTo("RESPONSE");
        assertThat(entry.processInstanceId()).isEqualTo("PID-ORIGINAL");
        assertThat(entry.businessKey()).isEqualTo("run-42");
    }

    @Test
    void aProxyDelegateLineIsClassifiedProxySide() {
        RuntimeEventLog.Entry entry = RuntimeEventLog.buildEntry(3, 3000L, "INFO", "com.tp.TargetPlatform.x",
                "PROXY DELEGATE INVOKED: delegate=StepOne bean=stepOne processDefinitionId=Flow:1:1 "
                        + "processInstanceId=PID activityId=StepOne activityName=Step One "
                        + "activityInstanceId=ai1 businessKey=run-42");

        assertThat(entry.kind()).isEqualTo("DELEGATE");
        assertThat(entry.side()).isEqualTo("PROXY");
    }

    @Test
    void aTwinDelegateLineIsClassifiedTwinSide() {
        RuntimeEventLog.Entry entry = RuntimeEventLog.buildEntry(4, 4000L, "INFO", "com.tp.TargetPlatform.x",
                "TWIN DELEGATE COMPLETED: delegate=StepOne activityId=StepOne processInstanceId=PID "
                        + "result=OK");

        assertThat(entry.kind()).isEqualTo("DELEGATE");
        assertThat(entry.side()).isEqualTo("TWIN");
    }

    @Test
    void aCapabilityLineExposesItsProcessIdentityWithoutGuessingASide() {
        RuntimeEventLog.Entry entry = RuntimeEventLog.buildEntry(5, 5000L, "INFO", "com.tp.TargetPlatform.x",
                "CAPABILITY DISPATCH: activity=StepOne processInstanceId=PID-ORIGINAL businessKey=run-42 "
                        + "providerIdentity=validator executor=ValidatorExecutor contract=none");

        assertThat(entry.kind()).isEqualTo("CAPABILITY");
        assertThat(entry.processInstanceId()).isEqualTo("PID-ORIGINAL");
        assertThat(entry.businessKey()).isEqualTo("run-42");
        assertThat(entry.side()).isNull();
    }

    @Test
    void anUnpairedCapabilityLineRemainsNeutral() {
        RuntimeEventLog.Entry entry = RuntimeEventLog.buildEntry(6, 6000L, "ERROR", "com.tp.TargetPlatform.x",
                "CAPABILITY TECHNICAL_FAILURE: activity=StepOne processInstanceId=PID-UNKNOWN "
                        + "businessKey=run-unknown providerIdentity=validator exception=boom");

        assertThat(entry.kind()).isEqualTo("ERROR");
        assertThat(entry.processInstanceId()).isEqualTo("PID-UNKNOWN");
        assertThat(entry.businessKey()).isEqualTo("run-unknown");
        assertThat(entry.side()).isNull();
    }

    @Test
    void anUnstructuredLogLineIsClassifiedSystemSideWithNoProtocolFields() {
        RuntimeEventLog.Entry entry = RuntimeEventLog.buildEntry(7, 7000L, "INFO", "com.tp.TargetPlatform.x",
                "some unrelated platform log line");

        assertThat(entry.kind()).isEqualTo("LOG");
        assertThat(entry.side()).isEqualTo("SYSTEM");
        assertThat(entry.direction()).isNull();
        assertThat(entry.processInstanceId()).isNull();
        assertThat(entry.businessKey()).isNull();
    }

    @Test
    void anErrorLevelLineIsClassifiedErrorRegardlessOfWording() {
        RuntimeEventLog.Entry entry = RuntimeEventLog.buildEntry(8, 8000L, "ERROR", "com.tp.TargetPlatform.x",
                "TASK: publish NOT confirmed for signal 'sync_StepOne' execution EXEC-1 "
                        + "(processInstanceId=PID, businessKey=run-42): boom");

        assertThat(entry.kind()).isEqualTo("ERROR");
    }
}
