import React from "react";
import { render, screen, waitFor, act, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Routes, Route, useLocation } from "react-router-dom";

import ModelPage from "./ModelPage";
import { listProjects } from "../../services/workbench/ProjectService";
import { saveModel, getModel, getWorkflowState, listModelSummaries, listTenants } from "../../services/workbench/WorkbenchService";
import { readLastOpenedModel, rememberLastOpenedModel } from "./lastOpenedModel";

// name has to start with "mock" to be referenced from a jest.mock factory below
const mockModelXml = "<definitions id=\"test-model\" />";
// what the hook reports as the bpmn:Process name (as edited in the properties panel); tests set it to simulate a panel edit
let mockProcessName = null;
const mockSetProcessName = jest.fn();

jest.mock("../../services/workbench/WorkbenchService", () => ({
    saveModel: jest.fn(),
    getModel: jest.fn(),
    getWorkflowState: jest.fn(),
    listModelSummaries: jest.fn(),
    listTenants: jest.fn(),
}));

jest.mock("../../services/workbench/ProjectService", () => ({
    listProjects: jest.fn(),
}));

// the real hook boots bpmn-js, which ships untransformed ESM and wants a live canvas to attach to -
// neither belongs in a test of this page's save rules. currentXml is the only part these tests
// actually depend on: it's what handleSave sends to the backend.
jest.mock("../../components/bpmn/useBpmnModeler", () => ({
    __esModule: true,
    default: () => ({
        canvasRef: { current: null },
        propertiesPanelRef: { current: null },
        modelerRef: { current: null },
        selected: null,
        selectedActivityId: null,
        importXml: jest.fn().mockResolvedValue(undefined),
        currentXml: jest.fn().mockResolvedValue(mockModelXml),
        processName: mockProcessName,
        setProcessName: mockSetProcessName,
    }),
}));

// pulls in bpmn-js the same way, and renders nothing without a selected element anyway
jest.mock("../../components/bpmn/DataPanel", () => ({
    __esModule: true,
    default: () => null,
}));

// omitting timestamp keeps WorkflowProgress's title exactly the status string, so a title query
// doesn't depend on the machine's locale date formatting (see its own title logic)
const stage = (status, detail) => (detail ? { status, detail } : { status });

const NOTHING_YET = {
    currentStage: "MODEL",
    stages: { MODEL: stage("PENDING"), GENERATE: stage("PENDING"), LAUNCH: stage("PENDING") },
};

const SAVED = {
    currentStage: "GENERATE",
    stages: {
        MODEL: stage("COMPLETED", "model saved"),
        GENERATE: stage("PENDING"),
        LAUNCH: stage("PENDING"),
    },
};

// Generate/Launch are triggered from their own Transmute pickers now (see
// GenerateProjectListPage / LaunchProjectListPage), not from this page - this fixture is what
// reopening a model already generated elsewhere looks like.
const GENERATED = {
    currentStage: "LAUNCH",
    stages: {
        MODEL: stage("COMPLETED", "model saved"),
        GENERATE: stage("COMPLETED", "proj-9"),
        LAUNCH: stage("PENDING"),
    },
};

        // Verifies unowned models save successfully and display confirmation with model ID.
let backendWorkflowState;

const button = (name) => screen.getByRole("button", { name });

const renderPage = () => render(
    <MemoryRouter initialEntries={[{ pathname: "/wb/model", state: { projectId: "7" } }]}>
        <ModelPage />
    </MemoryRouter>
);

// Real routes, for the tests about where the editor sends the URL: the resume redirect and Save moving onto the saved id.
const LocationProbe = () => <div data-testid="location">{useLocation().pathname}</div>;
const renderRouted = (pathname, state = { projectId: "7" }) => render(
    <MemoryRouter initialEntries={[{ pathname, state }]}>
        <LocationProbe />
        <Routes>
            <Route path="/wb/model" element={<ModelPage />} />
            <Route path="/wb/model/new" element={<ModelPage />} />
            <Route path="/wb/model/:id" element={<ModelPage />} />
        </Routes>
    </MemoryRouter>
);
const currentPath = () => screen.getByTestId("location").textContent;

const saveTheModel = async () => {
    userEvent.click(button("Save"));
    await waitFor(() => expect(saveModel).toHaveBeenCalled());
    await waitFor(() => expect(button("Save")).toBeEnabled());
};

describe("ModelPage - save", () => {
    beforeEach(() => {
        jest.clearAllMocks();
        window.localStorage.clear();
        mockProcessName = null;
        backendWorkflowState = NOTHING_YET;
        listModelSummaries.mockResolvedValue([]);
        getWorkflowState.mockImplementation(async () => backendWorkflowState);
        listProjects.mockResolvedValue([{ id: "7", displayName: "RedCollar Suits", name: "redcollar_suits" }]);
        listTenants.mockResolvedValue([]);
        getModel.mockResolvedValue({ id: "m-1", name: "New Process", bpmnXml: mockModelXml });
        saveModel.mockResolvedValue({ id: "m-1", name: "New Process" });
    });

    describe("handleSave", () => {
        test("sends the current diagram XML and the model name", async () => {
            renderPage();
            expect(await screen.findByRole("option", { name: "RedCollar Suits" })).toBeInTheDocument();

            await saveTheModel();

            // tenantId is always sent, "" normalized to null - see handleSave's own comment on why
            // the persisted Project id is also required now that Save truly attaches the model to a project.
            expect(saveModel).toHaveBeenCalledWith({
                name: "New Process",
                bpmnXml: mockModelXml,
                tenantId: null,
                projectId: 7,
            });
            expect(await screen.findByText(/Saved model "New Process" \(id m-1\)/)).toBeInTheDocument();
        });

        // the name field and the properties panel's process name are one value - see handleModelNameChange
        test("typing in the name field renames the bpmn:Process too, so the saved name matches the XML", async () => {
            renderPage();
            expect(await screen.findByRole("option", { name: "RedCollar Suits" })).toBeInTheDocument();

            const nameField = screen.getByPlaceholderText("Model name");
            userEvent.clear(nameField);
            userEvent.type(nameField, "Suit Fitting");

            expect(mockSetProcessName).toHaveBeenLastCalledWith("Suit Fitting");
            await saveTheModel();
            expect(saveModel).toHaveBeenCalledWith(expect.objectContaining({ name: "Suit Fitting" }));
        });

        test("a process name edited in the properties panel becomes the saved model name", async () => {
            mockProcessName = "Renamed In Panel";
            renderPage();
            expect(await screen.findByRole("option", { name: "RedCollar Suits" })).toBeInTheDocument();

            expect(screen.getByPlaceholderText("Model name")).toHaveValue("Renamed In Panel");
            await saveTheModel();
            expect(saveModel).toHaveBeenCalledWith(expect.objectContaining({ name: "Renamed In Panel" }));
        });

        // Second Save of the same model must be an update, not a second catalogue entry - see the backend's live-id path in WorkbenchServiceImpl.doSaveProcessModelEntry.
        test("saving again sends the id from the first save, so the backend updates instead of duplicating", async () => {
            renderPage();
            expect(await screen.findByRole("option", { name: "RedCollar Suits" })).toBeInTheDocument();

            await saveTheModel();
            expect(saveModel).toHaveBeenLastCalledWith(expect.not.objectContaining({ id: expect.anything() }));

            await saveTheModel();
            expect(saveModel).toHaveBeenCalledTimes(2);
            expect(saveModel).toHaveBeenLastCalledWith(expect.objectContaining({ id: "m-1", name: "New Process" }));
        });

        test("saving a model opened by id sends that id", async () => {
            renderRouted("/wb/model/m-1");
            expect(await screen.findByText(/Loaded "New Process"/)).toBeInTheDocument();

            await saveTheModel();
            expect(saveModel).toHaveBeenLastCalledWith(expect.objectContaining({ id: "m-1" }));
        });

        test("disables Save while the request is in flight, re-enables it after", async () => {
            let resolveSave;
            saveModel.mockReturnValue(new Promise((resolve) => {
                resolveSave = resolve;
            }));

            renderPage();
            userEvent.click(button("Save"));

            await waitFor(() => expect(button("Save")).toBeDisabled());

            backendWorkflowState = SAVED;
            await act(async () => {
                resolveSave({ id: "m-1", name: "New Process" });
            });

            await waitFor(() => expect(button("Save")).toBeEnabled());
        });

        test("reports a failed save", async () => {
            saveModel.mockRejectedValue(new Error("Boom"));

            renderPage();
            userEvent.click(button("Save"));

            expect(await screen.findByText(/Save failed: Boom/)).toBeInTheDocument();
        });

        test("Back to project processes, Save, Generate, and Launch buttons appear",
            async () => {
                renderPage();
                await screen.findByRole("option", { name: "RedCollar Suits" });

                expect(screen.getByRole("button", { name: "Save" })).toBeInTheDocument();
                expect(screen.getByRole("button", { name: "Back to project processes" })).toBeInTheDocument();
                expect(screen.getByRole("button", { name: "Generate" })).toBeInTheDocument();
                expect(screen.getByRole("button", { name: "Launch" })).toBeInTheDocument();
            });

        test("a status message never shares its row with the action buttons", async () => {
            renderPage();
            await saveTheModel();

            const message = await screen.findByText(/Saved model "New Process" \(id m-1\)/);
            const messageRow = message.closest(".bpmn-toolbar-row");
            const saveButtonRow = button("Save").closest(".bpmn-toolbar-row");
            expect(messageRow).not.toBe(saveButtonRow);
        });
    });

    // Transmute > Model must bring back the model being worked on, not a blank canvas - the resume pointer is written on load and on save.
    describe("resuming the last model", () => {
        test("Save moves the URL onto the saved id and remembers it for next time", async () => {
            renderRouted("/wb/model/new");
            expect(await screen.findByRole("option", { name: "RedCollar Suits" })).toBeInTheDocument();

            await saveTheModel();

            await waitFor(() => expect(currentPath()).toBe("/wb/model/m-1"));
            expect(readLastOpenedModel()).toEqual({ id: "m-1", projectId: "7" });
            // the URL change must not reload the model the editor already holds
            expect(getModel).not.toHaveBeenCalled();
            expect(screen.getByText(/Saved model "New Process" \(id m-1\)/)).toBeInTheDocument();
        });

        test("/wb/model with a remembered model redirects into that model's editor", async () => {
            rememberLastOpenedModel("m-1", "7");

            renderRouted("/wb/model", undefined);

            await waitFor(() => expect(currentPath()).toBe("/wb/model/m-1"));
            await waitFor(() => expect(getModel).toHaveBeenCalledWith("m-1"));
            expect(await screen.findByText(/Loaded "New Process"/)).toBeInTheDocument();
        });

        test("/wb/model with nothing remembered stays a blank editor", async () => {
            renderRouted("/wb/model", undefined);
            expect(await screen.findByRole("option", { name: "RedCollar Suits" })).toBeInTheDocument();

            expect(currentPath()).toBe("/wb/model");
            expect(getModel).not.toHaveBeenCalled();
        });

        test("opening a model by URL alone recovers its project from the summaries", async () => {
            listModelSummaries.mockResolvedValue([{ id: "m-1", name: "New Process", projectId: 7 }]);

            renderRouted("/wb/model/m-1", undefined);

            expect(await screen.findByText(/Loaded "New Process"/)).toBeInTheDocument();
            await waitFor(() => expect(screen.getByRole("option", { name: "RedCollar Suits" }).selected).toBe(true));
            expect(readLastOpenedModel()).toEqual({ id: "m-1", projectId: "7" });
        });

        test("a remembered model that no longer loads is forgotten", async () => {
            rememberLastOpenedModel("m-gone", "7");
            getModel.mockRejectedValue(new Error("not found"));

            renderRouted("/wb/model", undefined);

            expect(await screen.findByText(/Load failed: not found/)).toBeInTheDocument();
            expect(readLastOpenedModel()).toBeNull();
        });

        test("New model clears the editor back to a blank canvas at /wb/model/new", async () => {
            renderRouted("/wb/model/m-1");
            expect(await screen.findByText(/Loaded "New Process"/)).toBeInTheDocument();

            userEvent.click(button("New model"));

            await waitFor(() => expect(currentPath()).toBe("/wb/model/new"));
            expect(await screen.findByText(/New model. Nothing is saved yet/)).toBeInTheDocument();
        });
    });

    describe("workflow progress indicator", () => {
        test("starts with every stage pending", async () => {
            renderPage();

            const progress = within(screen.getByRole("navigation", { name: /progress/i }));
            expect(progress.getByText("Model")).toBeInTheDocument();
            expect(progress.getByText("Generate")).toBeInTheDocument();
            expect(progress.getByText("Launch")).toBeInTheDocument();
            // nothing fetched yet - no saved model id to fetch state for
            expect(progress.getAllByTitle("PENDING")).toHaveLength(3);
            expect(getWorkflowState).not.toHaveBeenCalled();
        });

        test("shows MODEL completed right after a save", async () => {
            renderPage();
            backendWorkflowState = SAVED;
            await saveTheModel();

            await waitFor(() => expect(screen.getByTitle("COMPLETED: model saved")).toBeInTheDocument());
        });

        // Generate/Launch happen on their own pages now (see GenerateProjectListPage /
        // LaunchProjectListPage) - this page only ever learns their outcome by loading the
        // model, which is exactly what reopening it for editing does.
        test("reopening a model that was already generated (from the Generate picker, elsewhere) "
            + "shows GENERATE completed without ever clicking anything here", async () => {
            backendWorkflowState = GENERATED;

            renderPage();
            await saveTheModel();

            await waitFor(() => expect(screen.getByTitle("COMPLETED: proj-9")).toBeInTheDocument());
        });

        test("saving a model that was already generated says the generation is now stale", async () => {
            backendWorkflowState = GENERATED;
            renderRouted("/wb/model/m-1");
            await screen.findByTitle("COMPLETED: proj-9");

            backendWorkflowState = SAVED;
            await saveTheModel();

            expect(await screen.findByText(/press Generate again/)).toBeInTheDocument();
            // the breadcrumb follows the backend: GENERATE is pending again
            await waitFor(() => expect(screen.queryByTitle("COMPLETED: proj-9")).not.toBeInTheDocument());
        });

        test("View details is disabled until there is workflow state to show", async () => {
            renderPage();

            const viewDetails = screen.getByRole("button", { name: /View details/ });
            expect(viewDetails).toBeDisabled();

            backendWorkflowState = SAVED;
            await saveTheModel();

            await waitFor(() => expect(screen.getByRole("button", { name: /View details/ })).toBeEnabled());
        });
    });
});
