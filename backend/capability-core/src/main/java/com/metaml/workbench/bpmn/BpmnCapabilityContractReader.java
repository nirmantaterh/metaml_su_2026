package com.metaml.workbench.bpmn;

import com.metaml.workbench.capability.CapabilityContract;
import com.metaml.workbench.capability.ExecutionMode;
import com.metaml.workbench.capability.IoDeclaration;
import com.metaml.workbench.capability.IoType;

import org.camunda.bpm.model.bpmn.BpmnModelInstance;
import org.camunda.bpm.model.bpmn.instance.Activity;
import org.camunda.bpm.model.bpmn.instance.ExclusiveGateway;
import org.camunda.bpm.model.bpmn.instance.FlowNode;
import org.camunda.bpm.model.bpmn.instance.SequenceFlow;
import org.camunda.bpm.model.bpmn.instance.DataInput;
import org.camunda.bpm.model.bpmn.instance.DataOutputAssociation;
import org.camunda.bpm.model.bpmn.instance.DataStore;
import org.camunda.bpm.model.bpmn.instance.ExtensionElements;
import org.camunda.bpm.model.bpmn.instance.FlowElement;
import org.camunda.bpm.model.bpmn.instance.IoSpecification;
import org.camunda.bpm.model.bpmn.instance.ItemAwareElement;
import org.camunda.bpm.model.bpmn.instance.ItemDefinition;
import org.camunda.bpm.model.bpmn.instance.Property;
import org.camunda.bpm.model.bpmn.instance.camunda.CamundaInputOutput;
import org.camunda.bpm.model.bpmn.instance.camunda.CamundaInputParameter;
import org.camunda.bpm.model.xml.instance.DomElement;
import org.camunda.bpm.model.xml.instance.ModelElementInstance;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

// Pure, deterministic BPMN -> CapabilityContract derivation (MetaML Scope 6, Phase 1).
//
// Reads an already-parsed BpmnModelInstance via the Camunda BPMN model API and derives, per
// Activity, the capability contract implied by the process model itself:
//
//   - required inputs: ioSpecification/dataInput (filtered of jBPM/Drools-modeler boilerplate,
//     with drools:dtype mapped to IoType) unioned with camunda:inputOutput/inputParameter names.
//   - declared outputs: dataOutputAssociation.targetRef (resolved to its ItemAwareElement and
//     typed from that element's itemSubjectRef/structureRef where present) unioned with
//     metaml:agentOutputs.
//   - required outputs: the process variables referenced by outgoing ExclusiveGateway condition
//     expressions on this activity's successor gateway, unioned with metaml:agentOutputs.
//   - unsatisfied outputs: required outputs not present in declared outputs.
//
// This is a static interpretation layer only. It never mutates the model, never touches process
// instance state, and never calls a provider, RabbitMQ, Ollama, or any other external service.
// Explicit metaml:capabilityContract, provider self-registration, capability-gap persistence, and
// every other later-phase concern are intentionally out of scope here (see the Phase 1 design
// lock) - this class only makes the static facts available for those later phases to consume.
public final class BpmnCapabilityContractReader {

    private BpmnCapabilityContractReader() {
    }

    private static final String CAMUNDA_NS = "http://camunda.org/schema/1.0/bpmn";
    private static final String DROOLS_NS = "http://www.jboss.org/drools";
    private static final String METAML_NS = "http://metaml.com/schema/bpmn/metaml";
    private static final String EXTERNAL_IMPLEMENTATION = "external";
    private static final String AGENT_OUTPUTS_ELEMENT = "agentOutputs";
    private static final String AGENT_OUTPUT_ELEMENT = "agentOutput";

    // Same rule enforced at every other point where a name lands as a Camunda process variable:
    // IoDeclaration, AgentOutputDeclarations.SAFE_VARIABLE_NAME, NodeManagerClient.SAFE_OUTPUT_NAME.
    // Duplicated here (rather than depending on AgentOutputDeclarations - see metamlAgentOutputs)
    // so a BPMN name that would fail IoDeclaration's own constructor is filtered out before
    // construction instead of throwing out of a pure derivation call.
    private static final Pattern SAFE_NAME = Pattern.compile("^[A-Za-z][A-Za-z0-9]*$");

    // jBPM/Drools-modeler generates these on every human/service task's ioSpecification regardless
    // of business content; they are task-management plumbing, never genuine capability inputs.
    private static final Set<String> IOSPEC_BOILERPLATE_INPUTS = Set.of(
            "TaskName", "Skippable", "GroupId", "Priority", "Comment", "Content", "Locale",
            "CreatedBy", "NotStartedNotify", "NotCompletedNotify", "NotStartedReassign",
            "NotCompletedReassign");

    private static final Map<String, IoType> TYPE_MAPPING = buildTypeMapping();

    private static Map<String, IoType> buildTypeMapping() {
        Map<String, IoType> mapping = new HashMap<>();
        mapping.put("String", IoType.STRING);
        mapping.put("Integer", IoType.NUMBER);
        mapping.put("Long", IoType.NUMBER);
        mapping.put("Float", IoType.NUMBER);
        mapping.put("Double", IoType.NUMBER);
        mapping.put("Short", IoType.NUMBER);
        mapping.put("BigDecimal", IoType.NUMBER);
        mapping.put("Boolean", IoType.BOOLEAN);
        mapping.put("Object", IoType.STRUCT);
        return Collections.unmodifiableMap(mapping);
    }

    // The static derivation result for one activity: the CapabilityContract a provider bound to
    // this activity would need to satisfy, plus the raw required/declared/unsatisfied output name
    // sets that later phases need for capability-gap detection. Nothing here is persisted, acted
    // on, or used to pick/execute a provider - Phase 1 stops at making the facts available.
    public record ActivityCapabilityDerivation(String activityId, CapabilityContract contract,
            Set<String> requiredOutputs, Set<String> declaredOutputs, Set<String> unsatisfiedOutputs) {

        public ActivityCapabilityDerivation {
            Objects.requireNonNull(activityId, "activityId must not be null");
            Objects.requireNonNull(contract, "contract must not be null");
            requiredOutputs = immutableCopy(requiredOutputs);
            declaredOutputs = immutableCopy(declaredOutputs);
            unsatisfiedOutputs = immutableCopy(unsatisfiedOutputs);
        }
    }

    // Derives the capability contract and static gap information for every Activity in the model.
    public static Map<String, ActivityCapabilityDerivation> deriveAll(BpmnModelInstance model) {
        Objects.requireNonNull(model, "model must not be null");
        Map<String, Set<String>> gatewayVarsByActivityId =
                gatewayConditionVariablesByActivityId(model);

        Map<String, ActivityCapabilityDerivation> result = new LinkedHashMap<>();
        for (Activity activity : model.getModelElementsByType(Activity.class)) {
            result.put(activity.getId(), derive(activity, gatewayVarsByActivityId));
        }
        return Collections.unmodifiableMap(result);
    }

    // Derives the capability contract and static gap information for a single Activity.
    public static ActivityCapabilityDerivation derive(BpmnModelInstance model, Activity activity) {
        Objects.requireNonNull(model, "model must not be null");
        Objects.requireNonNull(activity, "activity must not be null");
        return derive(activity, gatewayConditionVariablesByActivityId(model));
    }

    private static ActivityCapabilityDerivation derive(Activity activity,
            Map<String, Set<String>> gatewayVarsByActivityId) {
        // metaml:agentOutputs is keyed output-name -> declared process-variable name; the
        // process-variable name is what gateway conditions and every other output source
        // reference, so that is the identity used throughout (see IoDeclaration: "names land as
        // Camunda process variables").
        Set<String> metamlVariableNames = new LinkedHashSet<>(metamlAgentOutputs(activity).values());

        Set<String> gatewayVars = gatewayVarsByActivityId.getOrDefault(activity.getId(), Set.of());

        Set<IoDeclaration> declaredOutputDeclarations = declaredOutputs(activity, metamlVariableNames);
        Set<String> declaredOutputNames = namesOf(declaredOutputDeclarations);

        Set<String> requiredOutputNames = new LinkedHashSet<>(gatewayVars);
        requiredOutputNames.addAll(metamlVariableNames);

        Set<String> unsatisfiedOutputNames = new LinkedHashSet<>(requiredOutputNames);
        unsatisfiedOutputNames.removeAll(declaredOutputNames);

        CapabilityContract contract = new CapabilityContract(
                null,
                requiredInputs(activity),
                declaredOutputDeclarations,
                executionMode(activity),
                Map.of(),
                Set.of());

        return new ActivityCapabilityDerivation(activity.getId(), contract,
                requiredOutputNames, declaredOutputNames, unsatisfiedOutputNames);
    }

    // ---- Required inputs (design lock section 9) ----

    private static Set<IoDeclaration> requiredInputs(Activity activity) {
        Map<String, IoDeclaration> byName = new LinkedHashMap<>();

        // camunda:inputOutput ranks above ioSpecification/data associations in the locked
        // precedence order, so it is added first and wins on a name collision.
        ExtensionElements extensionElements = activity.getExtensionElements();
        if (extensionElements != null) {
            List<CamundaInputOutput> inputOutputs =
                    extensionElements.getElementsQuery().filterByType(CamundaInputOutput.class).list();
            for (CamundaInputOutput inputOutput : inputOutputs) {
                for (CamundaInputParameter parameter : inputOutput.getCamundaInputParameters()) {
                    String name = trimmedOrNull(parameter.getCamundaName());
                    if (name != null && SAFE_NAME.matcher(name).matches()) {
                        byName.putIfAbsent(name, new IoDeclaration(name, IoType.UNKNOWN, true));
                    }
                }
            }
        }

        IoSpecification ioSpecification = activity.getIoSpecification();
        if (ioSpecification != null) {
            for (DataInput dataInput : ioSpecification.getDataInputs()) {
                String name = trimmedOrNull(dataInput.getName());
                if (name == null || IOSPEC_BOILERPLATE_INPUTS.contains(name)
                        || !SAFE_NAME.matcher(name).matches()) {
                    continue;
                }
                String dtype = dataInput.getAttributeValueNs(DROOLS_NS, "dtype");
                byName.putIfAbsent(name, new IoDeclaration(name, mapType(dtype), true));
            }
        }

        return new LinkedHashSet<>(byName.values());
    }

    // ---- Declared outputs (design lock sections 7 and 8) ----

    private static Set<IoDeclaration> declaredOutputs(Activity activity, Set<String> metamlVariableNames) {
        Map<String, IoDeclaration> byName = new LinkedHashMap<>();

        // metaml:agentOutputs ranks above dataOutputAssociation in the locked precedence order.
        for (String variable : metamlVariableNames) {
            byName.putIfAbsent(variable, new IoDeclaration(variable, IoType.UNKNOWN, true));
        }

        for (DataOutputAssociation association : activity.getDataOutputAssociations()) {
            ItemAwareElement target = resolveTarget(association);
            if (target == null) {
                continue;
            }
            String name = trimmedOrNull(nameOf(target));
            if (name == null || !SAFE_NAME.matcher(name).matches()) {
                continue;
            }
            byName.putIfAbsent(name, new IoDeclaration(name, typeOf(target), true));
        }

        return new LinkedHashSet<>(byName.values());
    }

    // BPMN 2.0 requires dataOutputAssociation/targetRef to resolve to an ItemAwareElement in the
    // same document (an unresolved IDREF fails XML schema validation at parse time, before this
    // reader ever sees the model). The try/catch only guards a hand-built BpmnModelInstance (e.g.
    // the Camunda fluent builder API in a test) that never bound a target at all.
    private static ItemAwareElement resolveTarget(DataOutputAssociation association) {
        try {
            return association.getTarget();
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ItemAwareElement itself carries no name; resolve it from whichever concrete BPMN element the
    // target actually is (Property, a FlowElement such as DataObject/DataObjectReference, or a
    // root-level DataStore), falling back to its id when no name is declared.
    private static String nameOf(ItemAwareElement element) {
        String name = null;
        if (element instanceof Property property) {
            name = property.getName();
        } else if (element instanceof FlowElement flowElement) {
            name = flowElement.getName();
        } else if (element instanceof DataStore dataStore) {
            name = dataStore.getName();
        }
        if (name == null || name.isBlank()) {
            name = element.getId();
        }
        return name;
    }

    private static IoType typeOf(ItemAwareElement element) {
        ItemDefinition item = element.getItemSubject();
        return item == null ? IoType.UNKNOWN : mapType(item.getStructureRef());
    }

    // ---- metaml:agentOutputs (design lock section 8) ----

    // Reads metaml:agentOutputs directly from the activity's own extension elements, returning
    // output-name -> declared process-variable name. This mirrors AgentOutputDeclarations' XML
    // representation exactly (same namespace, element and attribute names) rather than
    // introducing a new one; it is a separate, bounded read path - not a redesign - because
    // AgentOutputDeclarations requires a live RepositoryService and a deployed processDefinitionId,
    // while this reader must stay a pure, deployment-free static interpretation layer (design lock
    // section 14: no external services, no process engine state).
    private static Map<String, String> metamlAgentOutputs(Activity activity) {
        ExtensionElements extensionElements = activity.getExtensionElements();
        if (extensionElements == null) {
            return Map.of();
        }
        Map<String, String> declared = new LinkedHashMap<>();
        for (ModelElementInstance child : extensionElements.getElements()) {
            if (!isMetaml(child.getDomElement(), AGENT_OUTPUTS_ELEMENT)) {
                continue;
            }
            for (DomElement declaration : child.getDomElement().getChildElements()) {
                if (!isMetaml(declaration, AGENT_OUTPUT_ELEMENT)) {
                    continue;
                }
                String name = trimmedOrNull(declaration.getAttribute("name"));
                String variable = trimmedOrNull(declaration.getAttribute("variable"));
                if (name == null || variable == null || !SAFE_NAME.matcher(variable).matches()) {
                    continue;
                }
                declared.put(name, variable);
            }
        }
        return declared;
    }

    private static boolean isMetaml(DomElement element, String localName) {
        return METAML_NS.equals(element.getNamespaceURI()) && localName.equals(element.getLocalName());
    }

    // ---- Execution mode (design lock section 11) ----

    // camunda:type="external" is this repository's own established marker for an activity
    // completed asynchronously by a separate external-task worker (see
    // ExternalTaskWorkerGenerator); every other activity completes inline within the engine
    // transaction that reaches it.
    private static ExecutionMode executionMode(Activity activity) {
        String type = activity.getAttributeValueNs(CAMUNDA_NS, "type");
        return EXTERNAL_IMPLEMENTATION.equals(type) ? ExecutionMode.ASYNCHRONOUS : ExecutionMode.SYNCHRONOUS;
    }

    // ---- Type mapping (design lock section 10) ----

    private static IoType mapType(String structureRef) {
        if (structureRef == null || structureRef.isBlank()) {
            return IoType.UNKNOWN;
        }
        // Recognizes a fully-qualified class name (e.g. "java.math.BigDecimal") under its simple
        // name; this reads the declared type accurately rather than inferring or defaulting one.
        String simpleName = structureRef;
        int lastDot = simpleName.lastIndexOf('.');
        if (lastDot >= 0 && lastDot < simpleName.length() - 1) {
            simpleName = simpleName.substring(lastDot + 1);
        }
        return TYPE_MAPPING.getOrDefault(simpleName, IoType.UNKNOWN);
    }

    // ---- Shared helpers ----

    private static String trimmedOrNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static Set<String> namesOf(Set<IoDeclaration> declarations) {
        Set<String> names = new LinkedHashSet<>();
        for (IoDeclaration declaration : declarations) {
            names.add(declaration.name());
        }
        return names;
    }

    private static <T> Set<T> immutableCopy(Set<T> source) {
        return source == null ? Set.of() : Collections.unmodifiableSet(new LinkedHashSet<>(source));
    }

    // ---- Gateway condition variables (relocated here from ExternalTaskWorkerGenerator) ----
    //
    // This is the single interpretation of "which process variables does control flow downstream of
    // this activity actually read". It has always fed two things: the required-output half of the
    // contract derived below, and the generated worker's own guard. It lives here rather than in the
    // generator because a running Target Platform needs it too - to decide which provider outputs may
    // be published under their plain process-variable names - and a Target Platform cannot depend on
    // the generation module. ExternalTaskWorkerGenerator keeps a delegating method for its callers.
    //
    // No variable name is known in advance: names come out of the model's own EL expressions.

    // Simple variable references: ${varName} or ${!varName}
    private static final Pattern CONDITION_VAR_PATTERN = Pattern.compile("\\$\\{!?(\\w+)\\}");
    // Extracts variable names from execution.getVariable('...') calls in EL expressions.
    private static final Pattern GETTER_VAR_PATTERN =
            Pattern.compile("execution\\.getVariable\\(['\"]([^'\"]+)['\"]\\)");
    // EL reserved words that the regex may capture from literal expressions like ${false}.
    // These are not process variables and must not be treated as such.
    private static final Set<String> EL_RESERVED = Set.of(
            "true", "false", "null", "empty",
            "not", "and", "or", "eq", "ne", "lt", "gt", "le", "ge",
            "instanceof", "div", "mod");

    // Maps gateway condition variables by the BPMN activity element id that precedes the gateway.
    public static Map<String, Set<String>> gatewayConditionVariablesByActivityId(BpmnModelInstance model) {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        for (ExclusiveGateway gw : model.getModelElementsByType(ExclusiveGateway.class)) {
            Set<String> varNames = gatewayConditionVariables(gw);
            if (varNames.isEmpty()) {
                continue;
            }
            for (SequenceFlow incoming : gw.getIncoming()) {
                FlowNode source = incoming.getSource();
                if (source instanceof Activity) {
                    result.computeIfAbsent(source.getId(), k -> new LinkedHashSet<>()).addAll(varNames);
                }
            }
        }
        return result;
    }

    // Every process variable this gateway's outgoing conditions reference.
    public static Set<String> gatewayConditionVariables(ExclusiveGateway gw) {
        Set<String> varNames = new LinkedHashSet<>();
        for (SequenceFlow outgoing : gw.getOutgoing()) {
            if (outgoing.getConditionExpression() == null) {
                continue;
            }
            String expr = outgoing.getConditionExpression().getTextContent();
            if (expr == null) {
                continue;
            }
            Matcher simple = CONDITION_VAR_PATTERN.matcher(expr);
            while (simple.find()) {
                String name = simple.group(1);
                if (!EL_RESERVED.contains(name)) {
                    varNames.add(name);
                }
            }
            Matcher getter = GETTER_VAR_PATTERN.matcher(expr);
            while (getter.find()) {
                varNames.add(getter.group(1));
            }
        }
        return varNames;
    }
}
