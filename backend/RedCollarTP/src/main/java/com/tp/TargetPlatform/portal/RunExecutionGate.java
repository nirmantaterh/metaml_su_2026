package com.tp.TargetPlatform.portal;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.externaltask.LockedExternalTask;
import org.springframework.stereotype.Component;

/**
 * Run-scoped execution gate used by generated external-task pollers.  It never completes an
 * activity itself: it only decides whether a real worker may execute a task already created by
 * Camunda.  STEP grants one BPMN activity id across both members of a correlated pair; once both
 * sides have left that activity, the next activity remains held until another release.
 */
@Component
public class RunExecutionGate {
    public enum Mode { AUTOMATIC, STEP_WAITING, STEP_RUNNING }

    private record State(Mode mode, String activityId) { }

    private final RuntimeService runtimeService;
    private final Map<String, State> states = new ConcurrentHashMap<>();

    public RunExecutionGate(RuntimeService runtimeService) {
        this.runtimeService = runtimeService;
    }

    public void configure(String businessKey, String requestedMode) {
        if (businessKey == null || businessKey.isBlank()) return;
        states.put(businessKey, "STEP".equalsIgnoreCase(requestedMode)
                ? new State(Mode.STEP_WAITING, null) : new State(Mode.AUTOMATIC, null));
    }

    public void runAutomatically(String businessKey) { configure(businessKey, "AUTOMATIC"); }

    public void releaseNext(String businessKey) {
        if (businessKey != null && !businessKey.isBlank()) states.put(businessKey, new State(Mode.STEP_RUNNING, null));
    }

    public Mode mode(String businessKey) {
        return states.getOrDefault(businessKey, new State(Mode.AUTOMATIC, null)).mode();
    }

    public boolean mayExecute(LockedExternalTask task) {
        String key = task.getBusinessKey();
        State state = states.get(key);
        if (state == null || state.mode() == Mode.AUTOMATIC) return true;
        if (state.mode() == Mode.STEP_WAITING) return false;
        String activityId = task.getActivityId();
        if (state.activityId() == null) {
            states.replace(key, state, new State(Mode.STEP_RUNNING, activityId));
            return true;
        }
        return state.activityId().equals(activityId);
    }

    public void afterExecution(LockedExternalTask task) {
        String key = task.getBusinessKey();
        State state = states.get(key);
        if (state == null || state.mode() != Mode.STEP_RUNNING || !task.getActivityId().equals(state.activityId())) return;
        boolean stillAtActivity = runtimeService.createProcessInstanceQuery().processInstanceBusinessKey(key).active().list()
                .stream().anyMatch(instance -> runtimeService.getActiveActivityIds(instance.getId()).contains(state.activityId()));
        if (!stillAtActivity) states.replace(key, state, new State(Mode.STEP_WAITING, null));
    }
}
