import { useEffect, useRef, useState, useCallback } from "react";

import BpmnModeler from "bpmn-js/lib/Modeler";
import "bpmn-js/dist/assets/diagram-js.css";
import "bpmn-js/dist/assets/bpmn-font/css/bpmn.css";
import "bpmn-js/dist/assets/bpmn-js.css";

import TokenSimulationModule from "bpmn-js-token-simulation";
import "bpmn-js-token-simulation/assets/css/bpmn-js-token-simulation.css";

import { assertRenderableBpmn } from "./renderableBpmn";
import metamlModdle from "./moddle/metamlModdle.json";
import camundaModdle from "camunda-bpmn-moddle/resources/camunda.json";
import defaultDiagram from "./defaultDiagram";

import {
    BpmnPropertiesPanelModule,
    BpmnPropertiesProviderModule,
    CamundaPlatformPropertiesProviderModule,
} from "bpmn-js-properties-panel";
import "@bpmn-io/properties-panel/assets/properties-panel.css";
import camundaPlatformBehaviors from "camunda-bpmn-js-behaviors/lib/camunda-platform";

// not connectable. everything else (tasks, events, gateways) can link to a twin.
const NON_ACTIVITY_TYPES = ["bpmn:Process", "bpmn:Collaboration", "bpmn:SequenceFlow"];

// throws on a cold first paint (canvas has no layout yet) - zoom is cosmetic so just eat it
function fitViewport(modeler) {
    try {
        modeler.get("canvas").zoom("fit-viewport");
    } catch (e) {
        // ignore
    }
}

// The bpmn:Process the diagram is about. A plain process is the root element itself; in a collaboration the root is the bpmn:Collaboration and the process hangs off the first participant's processRef.
function findRootProcess(modeler) {
    try {
        const root = modeler.get("canvas").getRootElement();
        if (!root || !root.businessObject) return null;
        if (root.type === "bpmn:Process") {
            return { element: root, process: root.businessObject };
        }
        if (root.type === "bpmn:Collaboration") {
            const participant = modeler
                .get("elementRegistry")
                .filter((el) => el.type === "bpmn:Participant" && el.businessObject?.processRef)[0];
            return participant ? { element: participant, process: participant.businessObject.processRef } : null;
        }
    } catch (e) {
        // ignore: no diagram imported yet
    }
    return null;
}

// Shared canvas hook; handles both the editable (with properties panel) and read-only cases.
export default function useBpmnModeler({ withPropertiesPanel = true } = {}) {
    const canvasRef = useRef(null);
    const propertiesPanelRef = useRef(null);
    const modelerRef = useRef(null);

    const [selected, setSelected] = useState(null);
    // properties-panel edits only change modelerRef; bump revision to re-render consumers
    const [, setRevision] = useState(0);
    const bump = useCallback(() => setRevision((r) => r + 1), []);
    // name of the bpmn:Process in the XML - the same value the properties panel shows for the process (or participant), mirrored into React state so the page can keep its own name field in sync with it. null until a diagram is imported.
    const [processName, setProcessNameState] = useState(null);
    const syncProcessName = useCallback(() => {
        const found = findRootProcess(modelerRef.current);
        setProcessNameState(found ? found.process.name || "" : null);
    }, []);

    useEffect(() => {
        let destroyed = false;
        const container = canvasRef.current;
        const modeler = new BpmnModeler({
            container,
            propertiesPanel: withPropertiesPanel ? { parent: propertiesPanelRef.current } : undefined,
            moddleExtensions: { metaml: metamlModdle, camunda: camundaModdle },
            additionalModules: [
                TokenSimulationModule,
                ...(withPropertiesPanel
                    ? [BpmnPropertiesPanelModule, BpmnPropertiesProviderModule, CamundaPlatformPropertiesProviderModule]
                    : []),
                camundaPlatformBehaviors,
            ],
        });
        modelerRef.current = modeler;

        const rootAsSelection = () => {
            try {
                return modeler.get("canvas").getRootElement();
            } catch (e) {
                return null;
            }
        };

        const eventBus = modeler.get("eventBus");
        eventBus.on("selection.changed", (e) => {
            const next = e.newSelection && e.newSelection.length ? e.newSelection[0] : rootAsSelection();
            setSelected(next);
        });
        eventBus.on("commandStack.changed", () => {
            syncProcessName();
            bump();
        });
        eventBus.on("import.done", () => {
            setSelected(rootAsSelection());
            syncProcessName();
            bump();
        });

        modeler
            .importXML(defaultDiagram)
            .then(() => {
                if (!destroyed) fitViewport(modeler);
            })
            .catch(() => {
                // ignore
            });

        return () => {
            destroyed = true;
            modeler.destroy();
            // StrictMode runs this twice in dev on the same node, clear the leftover svg
            if (container) {
                container.innerHTML = "";
            }
        };
    }, [bump, syncProcessName, withPropertiesPanel]);

    // Writes the name into the bpmn:Process itself (through the command stack, so it's undoable and the properties panel picks it up), rather than only into page state - otherwise the page's name field and the process name in the saved XML drift apart.
    const setProcessName = useCallback((name) => {
        const modeler = modelerRef.current;
        const found = modeler ? findRootProcess(modeler) : null;
        if (!found || (found.process.name || "") === (name || "")) return;
        // empty -> undefined, matching what the properties panel writes when its name field is cleared
        const value = name || undefined;
        const modeling = modeler.get("modeling");
        if (found.element.type === "bpmn:Process") {
            modeling.updateProperties(found.element, { name: value });
        } else {
            modeling.updateModdleProperties(found.element, found.process, { name: value });
        }
    }, []);

    // guard BEFORE importXML - see assertRenderableBpmn: once bpmn-js has been handed a DI-less model the properties panel is wedged for the rest of the page's life
    const importXml = async (xml) => {
        assertRenderableBpmn(xml);
        await modelerRef.current.importXML(xml);
        fitViewport(modelerRef.current);
    };

    const currentXml = async () => {
        const { xml } = await modelerRef.current.saveXML({ format: true });
        return xml;
    };

    // __implicitroot_* leaks through as if the user picked an activity; "nothing selected" must be null.
    const isImplicitRoot = selected && typeof selected.id === "string"
        && selected.id.startsWith("__implicitroot");
    const selectedActivityId =
        selected && selected.id && !isImplicitRoot && !NON_ACTIVITY_TYPES.includes(selected.type)
            ? selected.id
            : null;

    return {
        canvasRef,
        propertiesPanelRef,
        modelerRef,
        selected,
        selectedActivityId,
        importXml,
        currentXml,
        processName,
        setProcessName,
    };
}
