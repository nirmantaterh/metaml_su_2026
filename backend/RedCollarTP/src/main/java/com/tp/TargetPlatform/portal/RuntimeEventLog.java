package com.tp.TargetPlatform.portal;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

// A bounded, in-memory tail of this Target Platform's OWN log records, so the portal's Messages and
// Runtime Logs pages show events that actually happened inside this JVM rather than anything the
// browser invented.
//
// Deliberately a log appender rather than a second event bus: the RabbitMQ TASK/RESPONSE lines, the
// generated delegate INVOKED/COMPLETED lines and the capability-runtime lines are already emitted by
// the proven generated messaging/delegate code and by capability-core. Re-publishing those same
// events through a parallel mechanism would mean either editing that proven code or maintaining a
// second, drift-prone source of truth. Nothing here fabricates an entry: an entry exists only
// because the corresponding logger call ran.
//
// Only this application's own packages are captured, so third-party framework noise cannot be
// mistaken for Target Platform activity.
@Component
public class RuntimeEventLog {

    private static final int MAX_ENTRIES = 3000;
    private static final List<String> CAPTURED_LOGGER_PREFIXES =
            List.of("com.tp.TargetPlatform", "com.metaml");
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault());

    // side: which half of a generated pair this event belongs to - PROXY, TWIN, or SYSTEM when the
    // event is neither (e.g. a plain platform log line). direction/signal/processInstanceId/
    // businessKey are populated only for TASK/RESPONSE/SIGNAL-kind entries whose message matched the
    // generated messaging layer's own fixed line shape (see classify() and PROTOCOL_MESSAGE below);
    // null otherwise, never guessed.
    public record Entry(long seq, String time, long epochMillis, String level, String logger,
            String message, String kind, String side, String direction, String signal,
            String processInstanceId, String businessKey) { }

    // Matches every TASK/RESPONSE/REQUEST/DELIVERED line the generated TaskQueuePublisher,
    // TaskQueueListener, ResponseQueuePublisher, ResponseQueueListener and SignalBroadcaster classes
    // emit (see TargetPlatformMessagingGenerator) - the wording between "signal '<name>'" and
    // "execution <id>" differs by class ("published ... to RabbitMQ exchange ... for execution" vs
    // "delivered ... to execution"), so the middle is matched non-greedily. This is the messaging
    // layer's own fixed, generic line shape - identical for any generated process - not anything
    // domain-specific.
    private static final Pattern PROTOCOL_MESSAGE = Pattern.compile(
            "^(TASK|RESPONSE|REQUEST|DELIVERED): .*?signal '([^']+)'.*?execution (\\S+) "
                    + "\\(processInstanceId=([^,]*), businessKey=([^)]*)\\)");

    private final Deque<Entry> entries = new ConcurrentLinkedDeque<>();
    private final AtomicLong sequence = new AtomicLong();
    private AppenderBase<ILoggingEvent> appender;

    @PostConstruct
    void attach() {
        if (!(LoggerFactory.getILoggerFactory() instanceof LoggerContext context)) {
            return;
        }
        appender = new AppenderBase<>() {
            @Override
            protected void append(ILoggingEvent event) {
                record(event);
            }
        };
        appender.setContext(context);
        appender.setName("metaml-portal-runtime-event-log");
        appender.start();
        Logger root = context.getLogger(Logger.ROOT_LOGGER_NAME);
        root.addAppender(appender);
    }

    @PreDestroy
    void detach() {
        if (appender == null) {
            return;
        }
        if (LoggerFactory.getILoggerFactory() instanceof LoggerContext context) {
            context.getLogger(Logger.ROOT_LOGGER_NAME).detachAppender(appender);
        }
        appender.stop();
    }

    private void record(ILoggingEvent event) {
        String loggerName = event.getLoggerName();
        if (loggerName == null || CAPTURED_LOGGER_PREFIXES.stream().noneMatch(loggerName::startsWith)) {
            return;
        }
        Entry entry = buildEntry(sequence.incrementAndGet(), event.getTimeStamp(),
                event.getLevel().toString(), loggerName, event.getFormattedMessage());
        entries.addLast(entry);
        while (entries.size() > MAX_ENTRIES) {
            entries.pollFirst();
        }
    }

    // Pure and package-private so a focused test can assert the parsing directly, without going
    // through the Logback appender machinery.
    static Entry buildEntry(long seq, long epochMillis, String level, String loggerName, String message) {
        String kind = classify(level, message);
        String direction = null;
        String signal = null;
        String processInstanceId = null;
        String businessKey = null;
        Matcher protocolMatch = message == null ? null : PROTOCOL_MESSAGE.matcher(message);
        if (protocolMatch != null && protocolMatch.find()) {
            // The word exactly as the generated code wrote it (TASK/RESPONSE/REQUEST/DELIVERED) - not
            // reinterpreted, so a caller correlating "side" does it against the processInstanceId
            // below (matched to whichever pair member actually owns that instance), not against an
            // assumption baked in here about which side a given word belongs to.
            direction = protocolMatch.group(1);
            signal = protocolMatch.group(2);
            // group(3) is the executionId ("execution <id>") - not captured as its own field, since
            // nothing here correlates by it; group(4)/(5) are the two named fields the line itself
            // labels processInstanceId=/businessKey=.
            processInstanceId = protocolMatch.group(4);
            businessKey = protocolMatch.group(5);
        }
        String side = sideOf(kind, message);
        return new Entry(seq, TIME.format(Instant.ofEpochMilli(epochMillis)), epochMillis, level,
                shortLoggerName(loggerName), message, kind, side, direction, signal, processInstanceId,
                businessKey);
    }

    // Classification uses only the platform's own generic log vocabulary - the literal prefixes the
    // generated TaskQueuePublisher/TaskQueueListener, ResponseQueuePublisher/ResponseQueueListener,
    // generated delegates and capability runtime emit. No process, activity or domain term is matched.
    private static String classify(String level, String message) {
        if ("ERROR".equals(level)) {
            return "ERROR";
        }
        if (message == null) {
            return "LOG";
        }
        if (message.startsWith("TASK:")) {
            return "TASK";
        }
        if (message.startsWith("RESPONSE:")) {
            return "RESPONSE";
        }
        if (message.startsWith("REQUEST:") || message.startsWith("DELIVERED:")) {
            return "SIGNAL";
        }
        if (message.contains("DELEGATE INVOKED") || message.contains("DELEGATE COMPLETED")
                || message.contains("INVOKED: listener=")) {
            return "DELEGATE";
        }
        if (message.startsWith("CAPABILITY")) {
            return "CAPABILITY";
        }
        return "LOG";
    }

    // PROXY/TWIN/SYSTEM, derived only from facts fixed by the generated code itself - never guessed
    // from business content:
    //  - a DELEGATE or LISTENER line's very first word is literally "PROXY" or "TWIN" (see
    //    TargetPlatformSourceGenerator's own label construction: side = twin ? "TWIN" : "PROXY").
    //  - a CAPABILITY line is architecturally always TWIN-side: CapabilityDispatcher.dispatch() is
    //    invoked only from a generated Twin external-task worker (see ExternalTaskWorkerGenerator's
    //    renderTwinWorkerSource - the plain proxy worker never references it).
    //  - a TASK/RESPONSE/SIGNAL line reports a specific processInstanceId (see the Entry field of the
    //    same name); which pair member that belongs to is for the caller to resolve by matching it
    //    against a known pair, not for this class to assume.
    private static String sideOf(String kind, String message) {
        // classify() puts both delegate and listener lines under "DELEGATE" - both label formats
        // start with the same PROXY/TWIN side word, so one check covers both.
        if ("DELEGATE".equals(kind) && message != null) {
            if (message.startsWith("PROXY")) return "PROXY";
            if (message.startsWith("TWIN")) return "TWIN";
        }
        if ("CAPABILITY".equals(kind)) {
            return "TWIN";
        }
        if ("TASK".equals(kind) || "RESPONSE".equals(kind) || "SIGNAL".equals(kind)) {
            return null;
        }
        return "SYSTEM";
    }

    private static String shortLoggerName(String loggerName) {
        int lastDot = loggerName.lastIndexOf('.');
        return lastDot < 0 ? loggerName : loggerName.substring(lastDot + 1);
    }

    // Newest first, optionally filtered to the message kinds the caller asked for.
    public List<Entry> tail(int limit, Collection<String> kinds) {
        List<Entry> snapshot = new ArrayList<>(entries);
        List<Entry> result = new ArrayList<>();
        for (int i = snapshot.size() - 1; i >= 0 && result.size() < limit; i--) {
            Entry entry = snapshot.get(i);
            if (kinds == null || kinds.isEmpty() || kinds.contains(entry.kind())) {
                result.add(entry);
            }
        }
        return result;
    }

    public int size() {
        return entries.size();
    }
}
