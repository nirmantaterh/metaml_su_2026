package com.example.camundademo.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import com.example.camundademo.bridge.NotificationBridge;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;

// Integration test verifying task publish, consumption, and response correlation over RabbitMQ.
@SpringBootTest
@TestPropertySource(properties = {
        "metaml.messaging.enabled=true",
        "spring.rabbitmq.host=localhost",
        "spring.rabbitmq.port=5672",
        "camunda.bpm.job-executor-activate=false"
})
class MessagingFlowIT {

    @Autowired
    private NotificationBridge notificationBridge;

    @Autowired
    private RabbitAdmin rabbitAdmin;

    private final List<String> logLines = new CopyOnWriteArrayList<>();
    private AppenderBase<ILoggingEvent> appender;

    @BeforeEach
    void captureLogs() {
        appender = new AppenderBase<>() {
            @Override
            protected void append(ILoggingEvent event) {
                logLines.add(event.getFormattedMessage());
            }
        };
        appender.start();
        ((Logger) LoggerFactory.getLogger("com.example.camundademo")).addAppender(appender);
    }

    @AfterEach
    void stopCapturing() {
        ((Logger) LoggerFactory.getLogger("com.example.camundademo")).detachAppender(appender);
        appender.stop();
    }

    @Test
    void declaresEveryQueueOnTheRealBroker() {
        assertThat(queueExists(MessagingTopology.MACHINES_REQUEST_QUEUE)).isTrue();
        assertThat(queueExists(MessagingTopology.MACHINES_COMPLETION_QUEUE)).isTrue();
        assertThat(queueExists(MessagingTopology.TWIN_STAGE_UPDATE_QUEUE)).isTrue();
        assertThat(queueExists(MessagingTopology.TWIN_STAGE_RESPONSE_QUEUE)).isTrue();
        assertThat(queueExists(MessagingTopology.GATEWAY_QC_REQUEST_QUEUE)).isTrue();
        assertThat(queueExists(MessagingTopology.GATEWAY_QC_RESPONSE_QUEUE)).isTrue();
    }

    @Test
    void manufacturingReachesTheTwinAndTheGatewayAndComesBackWithTheQcResult() {
        notificationBridge.notifyTwin("test-process-instance-1", "Task_Stitch");

        awaitLogContaining("[twin] received stage update for activity 'Task_Stitch'");
        awaitLogContaining("[gateway stub] executing QC for activity 'Task_Stitch'");
        awaitLogContaining("[twin] received QC response 'PASS' for activity 'Task_Stitch'");
        awaitLogContaining("[manufacturing] twin reported stage result 'PASS' for activity 'Task_Stitch'");
    }

    @Test
    void manufacturingCanRequestMachinesAndReceiveACompletion() {
        notificationBridge.requestMachines("test-process-instance-2", "Task_Assemble");

        awaitLogContaining("[machines stub] acquiring machines for activity 'Task_Assemble'");
        awaitLogContaining("[manufacturing] machines reported 'ACQUIRED' for activity 'Task_Assemble'");
    }

    private boolean queueExists(String queueName) {
        QueueInformation info = rabbitAdmin.getQueueInfo(queueName);
        return info != null;
    }

    private void awaitLogContaining(String fragment) {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(15));
        while (Instant.now().isBefore(deadline)) {
            if (logLines.stream().anyMatch(line -> line.contains(fragment))) {
                return;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new AssertionError("Timed out waiting for log line containing: " + fragment
                + "\nCaptured so far:\n" + String.join("\n", logLines));
    }
}
