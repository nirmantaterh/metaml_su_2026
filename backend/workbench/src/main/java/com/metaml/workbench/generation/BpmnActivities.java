package com.metaml.workbench.generation;

import org.camunda.bpm.model.bpmn.BpmnModelInstance;
import org.camunda.bpm.model.bpmn.instance.ReceiveTask;
import org.camunda.bpm.model.bpmn.instance.UserTask;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

// Identifies activities requiring completion endpoints based on wait-state execution semantics
// (e.g. user tasks, receive tasks, and external tasks where the engine parks execution).
public final class BpmnActivities {

    private static final String CAMUNDA_NS = "http://camunda.org/schema/1.0/bpmn";
    private static final String EXTERNAL_IMPLEMENTATION = "external";

    // How the endpoint must move this activity along. The URL shape stays uniform
    // (/<activity>/complete); only this differs underneath.
    public enum Trigger {
        // a real human task the engine created; TaskService completes it
        USER_TASK,
        // a wait state with no task behind it; the execution sitting there gets signalled
        RECEIVE_TASK,
        // the engine explicitly never runs these - an external worker must, which is exactly what a generated endpoint is
        EXTERNAL_TASK
    }

    // Activity metadata linking BPMN element id to its generated endpoint slug and trigger type.
    public record Activity(String id, String name, String endpointSlug, String methodSuffix, Trigger trigger) {
    }

    private BpmnActivities() {
    }

    // Queries BPMN Activity supertype to discover eligible wait-state tasks in document order.
    public static List<Activity> eligible(BpmnModelInstance model) {
        List<Activity> activities = new ArrayList<>();
        Set<String> usedSlugs = new LinkedHashSet<>();
        for (org.camunda.bpm.model.bpmn.instance.Activity element
                : model.getModelElementsByType(org.camunda.bpm.model.bpmn.instance.Activity.class)) {
            Trigger trigger = triggerFor(element);
            if (trigger == null) {
                continue;
            }
            String id = element.getId();
            String slug = disambiguate(preferredSlug(element.getName(), id), usedSlugs);
            activities.add(new Activity(id, element.getName(), slug, toMethodSuffix(slug), trigger));
        }
        return activities;
    }

        // External task wait state; completion endpoint allows driving task without external workers.
    private static Trigger triggerFor(org.camunda.bpm.model.bpmn.instance.Activity element) {
        if (EXTERNAL_IMPLEMENTATION.equals(element.getAttributeValueNs(CAMUNDA_NS, "type"))) {
            return Trigger.EXTERNAL_TASK;
        }
        if (element instanceof UserTask) {
            return Trigger.USER_TASK;
        }
        if (element instanceof ReceiveTask) {
            return Trigger.RECEIVE_TASK;
        }
        // Non-waiting activities (e.g. inline scripts, delegates, gateways) do not require advance triggers.
        return null;
    }

    // Generates URL slug preferring task display name, falling back to BPMN element ID.
    private static String preferredSlug(String name, String id) {
        String fromName = slugify(name);
        if (!fromName.isEmpty()) {
            return fromName;
        }
        String fromId = slugify(id);
        return fromId.isEmpty() ? "activity" : fromId;
    }

    // Discovers eligible wait-state activities (user, receive, external tasks) in document order.
    private static String slugify(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder slug = new StringBuilder(raw.length());
        boolean pendingSeparator = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c < 128 && Character.isLetterOrDigit(c)) {
                if (pendingSeparator && slug.length() > 0) {
                    slug.append('-');
                }
                slug.append(Character.toLowerCase(c));
                pendingSeparator = false;
            } else {
                pendingSeparator = true;
            }
        }
        return slug.toString();
    }

    // Element ids are unique, but slugs of them need not be - "Task_KYC" and "Task-KYC" both reduce to "task-kyc", and two activities can share a display name outright. Two endpoints mapped to the same path is a startup failure in the generated app, so collisions are resolved here instead, by document order, which keeps the output deterministic.
    private static String disambiguate(String slug, Set<String> usedSlugs) {
        String candidate = slug;
        int suffix = 2;
        while (!usedSlugs.add(candidate)) {
            candidate = slug + "-" + suffix;
            suffix++;
        }
        return candidate;
    }

    // Derived from the already-deduplicated slug so that unique slugs guarantee unique Java method names, rather than repeating the collision handling for identifiers.
    private static String toMethodSuffix(String slug) {
        StringBuilder name = new StringBuilder(slug.length());
        boolean capitalizeNext = true;
        for (int i = 0; i < slug.length(); i++) {
            char c = slug.charAt(i);
            if (c == '-') {
                capitalizeNext = true;
                continue;
            }
            name.append(capitalizeNext ? Character.toUpperCase(c) : c);
            capitalizeNext = false;
        }
        // a slug starting with a digit ("2nd-review") would otherwise render an illegal method name; the prefix keeps it a valid identifier without changing the URL
        if (name.isEmpty() || !Character.isJavaIdentifierStart(name.charAt(0))) {
            name.insert(0, "Activity");
        }
        return name.toString();
    }
}
