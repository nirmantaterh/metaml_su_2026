import React from "react";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";

import LaunchProjectListPage from "./LaunchProjectListPage";
import {
    listModelSummaries,
    getWorkflowState,
    launchProject,
    stopProject,
    listRunningProjects,
} from "../../services/workbench/WorkbenchService";
import { openCockpitUrl } from "../../components/workbench/openCockpitUrl";

jest.mock("../../services/workbench/WorkbenchService", () => ({
    listModelSummaries: jest.fn(),
    getWorkflowState: jest.fn(),
    launchProject: jest.fn(),
    stopProject: jest.fn(),
    listRunningProjects: jest.fn(),
}));

jest.mock("../../components/workbench/openCockpitUrl", () => ({
    __esModule: true,
    openCockpitUrl: jest.fn(),
}));

const button = (name) => screen.getByRole("button", { name });

describe("LaunchProjectListPage", () => {
    beforeEach(() => {
        jest.clearAllMocks();
        global.fetch = jest.fn();
        listRunningProjects.mockResolvedValue([]);
    });

    test("displays processes with Not Generated status when ungenerated, and Generated / Stopped when generated", async () => {
        listModelSummaries.mockResolvedValue([
            { id: "m-1", name: "Wire Transfer Review", projectId: 5, projectDisplayName: "RedCollar Suits" },
            { id: "m-2", name: "Never Generated", projectId: 6, projectDisplayName: "Loans" },
        ]);
        getWorkflowState.mockImplementation(async (id) => {
            if (id === "m-1") {
                return { stages: { GENERATE: { status: "COMPLETED", detail: "gp-1" } } };
            }
            return { stages: { GENERATE: { status: "PENDING" } } };
        });

        render(<LaunchProjectListPage />);

        expect(await screen.findByText("Wire Transfer Review")).toBeInTheDocument();
        expect(screen.getByText("Never Generated")).toBeInTheDocument();

        expect(screen.getByText("Not Generated")).toBeInTheDocument();
        expect(screen.getByText("Generated / Stopped")).toBeInTheDocument();
        expect(screen.getByText("—")).toBeInTheDocument();
        expect(screen.getByRole("button", { name: "Launch" })).toBeInTheDocument();
        expect(screen.queryByRole("button", { name: "Open Platform" })).not.toBeInTheDocument();
        expect(screen.queryByRole("button", { name: "Open Cockpit" })).not.toBeInTheDocument();
        expect(screen.queryByRole("button", { name: "Stop" })).not.toBeInTheDocument();
    });

    test("Launch starts platform, does NOT auto-open Cockpit or Target Platform, and visibly exposes controls when Running", async () => {
        listModelSummaries.mockResolvedValue([
            { id: "m-1", name: "Wire Transfer Review", projectId: 5, projectDisplayName: "RedCollar Suits" },
        ]);
        getWorkflowState.mockResolvedValue({ stages: { GENERATE: { status: "COMPLETED", detail: "gp-1" } } });
        launchProject.mockResolvedValue({
            port: 8091,
            processKey: "wireTransferReview",
            targetPlatformUrl: "https://tp.acme.internal/metaml",
        });
        global.fetch.mockImplementation(async (url) => ({
            ok: true,
            json: async () =>
                url.includes("/proxy/")
                    ? { processInstanceId: "pi-proxy", role: "initiator" }
                    : { processInstanceId: "pi-twin", role: "responder" },
        }));

        render(<LaunchProjectListPage />);
        await screen.findByText("Wire Transfer Review");

        userEvent.click(button("Launch"));

        await waitFor(() => expect(launchProject).toHaveBeenCalledWith({ projectId: "gp-1" }));
        await waitFor(() => expect(global.fetch).toHaveBeenCalledTimes(2));

        // CRITICAL: Launch must NOT auto-open Cockpit or navigate anywhere
        expect(openCockpitUrl).not.toHaveBeenCalled();

        // Status updates to Running
        expect(screen.getByText("Running")).toBeInTheDocument();

        // Running state visibly exposes the three explicit actions directly in the row
        expect(screen.getByRole("button", { name: "Open Platform" })).toBeInTheDocument();
        expect(screen.getByRole("button", { name: "Open Cockpit" })).toBeInTheDocument();
        expect(screen.getByRole("button", { name: "Stop" })).toBeInTheDocument();
        expect(screen.queryByRole("button", { name: "Launch" })).not.toBeInTheDocument();
        expect(screen.queryByRole("button", { name: "Open" })).not.toBeInTheDocument();

        // Runtime details are collapsed by default
        expect(screen.queryByText("8091")).not.toBeInTheDocument();
        expect(screen.queryByText("Started")).not.toBeInTheDocument();

        // Expanding details reveals engine and Target Platform endpoints without duplicate buttons
        const expandBtn = screen.getByRole("button", { name: "Expand details" });
        userEvent.click(expandBtn);
        expect(screen.getByText("8091")).toBeInTheDocument();
        expect(screen.getByText("Started")).toBeInTheDocument();
        expect(screen.getByText("https://tp.acme.internal/metaml")).toBeInTheDocument();

        // Verify no duplicate buttons in expanded panel
        expect(screen.getAllByRole("button", { name: "Open Platform" })).toHaveLength(1);
        expect(screen.getAllByRole("button", { name: "Open Cockpit" })).toHaveLength(1);
        expect(screen.getAllByRole("button", { name: "Stop" })).toHaveLength(1);
    });

    test("Open Platform uses configured targetPlatformUrl and opens only after explicit click", async () => {
        listModelSummaries.mockResolvedValue([
            { id: "m-1", name: "Wire Transfer Review", projectId: 5, projectDisplayName: "RedCollar Suits" },
        ]);
        getWorkflowState.mockResolvedValue({ stages: { GENERATE: { status: "COMPLETED", detail: "gp-1" } } });
        launchProject.mockResolvedValue({
            port: 8091,
            processKey: "wireTransferReview",
            targetPlatformUrl: "https://tp.acme.internal/metaml",
        });
        global.fetch.mockResolvedValue({ ok: true, json: async () => ({}) });

        render(<LaunchProjectListPage />);
        await screen.findByText("Wire Transfer Review");

        userEvent.click(button("Launch"));
        await screen.findByRole("button", { name: "Open Platform" });

        expect(openCockpitUrl).not.toHaveBeenCalled();

        // Explicit click opens Target Platform URL
        userEvent.click(screen.getByRole("button", { name: "Open Platform" }));
        expect(openCockpitUrl).toHaveBeenCalledTimes(1);
        expect(openCockpitUrl).toHaveBeenCalledWith("https://tp.acme.internal/metaml");
    });

    test("Open Platform falls back to runtime port root URL when targetPlatformUrl is not provided", async () => {
        listModelSummaries.mockResolvedValue([
            { id: "m-1", name: "Wire Transfer Review", projectId: 5, projectDisplayName: "RedCollar Suits" },
        ]);
        getWorkflowState.mockResolvedValue({ stages: { GENERATE: { status: "COMPLETED", detail: "gp-1" } } });
        launchProject.mockResolvedValue({
            port: 8095,
            processKey: "wireTransferReview",
        });
        global.fetch.mockResolvedValue({ ok: true, json: async () => ({}) });

        render(<LaunchProjectListPage />);
        await screen.findByText("Wire Transfer Review");

        userEvent.click(button("Launch"));
        const openPlatformBtn = await screen.findByRole("button", { name: "Open Platform" });

        expect(openCockpitUrl).not.toHaveBeenCalled();

        userEvent.click(openPlatformBtn);
        expect(openCockpitUrl).toHaveBeenCalledTimes(1);
        expect(openCockpitUrl).toHaveBeenCalledWith("http://127.0.0.1:8095/");
    });

    test("Open Cockpit opens Camunda URL only after explicit click", async () => {
        listModelSummaries.mockResolvedValue([
            { id: "m-1", name: "Wire Transfer Review", projectId: 5, projectDisplayName: "RedCollar Suits" },
        ]);
        getWorkflowState.mockResolvedValue({ stages: { GENERATE: { status: "COMPLETED", detail: "gp-1" } } });
        launchProject.mockResolvedValue({
            port: 8091,
            processKey: "wireTransferReview",
        });
        global.fetch.mockResolvedValue({ ok: true, json: async () => ({}) });

        render(<LaunchProjectListPage />);
        await screen.findByText("Wire Transfer Review");

        userEvent.click(button("Launch"));
        const openCockpitBtn = await screen.findByRole("button", { name: "Open Cockpit" });

        expect(openCockpitUrl).not.toHaveBeenCalled();

        userEvent.click(openCockpitBtn);
        expect(openCockpitUrl).toHaveBeenCalledTimes(1);
        expect(openCockpitUrl).toHaveBeenCalledWith("http://localhost:8091/camunda/app/cockpit/engine/");
    });

    test("clicking Stop calls stopProject, stops platform runtime, and returns to Generated / Stopped state with Launch button", async () => {
        listModelSummaries.mockResolvedValue([
            { id: "m-1", name: "Wire Transfer Review", projectId: 5, projectDisplayName: "RedCollar Suits" },
        ]);
        getWorkflowState.mockResolvedValue({ stages: { GENERATE: { status: "COMPLETED", detail: "gp-1" } } });
        launchProject.mockResolvedValue({ port: 8091, processKey: "wireTransferReview" });
        stopProject.mockResolvedValue({ success: true });
        global.fetch.mockResolvedValue({ ok: false, status: 404 });

        render(<LaunchProjectListPage />);
        await screen.findByText("Wire Transfer Review");

        userEvent.click(button("Launch"));
        await waitFor(() => expect(launchProject).toHaveBeenCalledWith({ projectId: "gp-1" }));

        // Stop is visibly available immediately when Running (no clicking Open first!)
        const stopBtn = await screen.findByRole("button", { name: "Stop" });
        expect(stopBtn).toBeInTheDocument();

        userEvent.click(stopBtn);

        await waitFor(() => expect(stopProject).toHaveBeenCalledWith({ projectId: "gp-1" }));
        expect(await screen.findByText("Generated / Stopped")).toBeInTheDocument();
        expect(screen.getByRole("button", { name: "Launch" })).toBeInTheDocument();
        expect(screen.queryByRole("button", { name: "Stop" })).not.toBeInTheDocument();
        expect(screen.queryByRole("button", { name: "Open Platform" })).not.toBeInTheDocument();
        expect(screen.queryByRole("button", { name: "Open Cockpit" })).not.toBeInTheDocument();
    });

    test("an app still running from a version that has since been re-saved shows Running with visible Stop button and can be stopped", async () => {
        listModelSummaries.mockResolvedValue([
            { id: "m-1", name: "Wire Transfer Review", projectId: 5, projectDisplayName: "RedCollar Suits" },
        ]);
        getWorkflowState.mockResolvedValue({ stages: { GENERATE: { status: "PENDING" } } });
        listRunningProjects.mockResolvedValue([
            { projectId: "gp-stale", modelId: "m-1", port: 8091, processKey: "wireTransferReview" },
        ]);
        stopProject.mockResolvedValue({ success: true });

        render(<LaunchProjectListPage />);
        await screen.findByText("Wire Transfer Review");

        expect(screen.getByText("Running")).toBeInTheDocument();
        expect(screen.queryByRole("button", { name: "Launch" })).not.toBeInTheDocument();

        // Stop is immediately visible and clickable
        const stopBtn = screen.getByRole("button", { name: "Stop" });
        expect(stopBtn).toBeInTheDocument();
        userEvent.click(stopBtn);

        await waitFor(() => expect(stopProject).toHaveBeenCalledWith({ projectId: "gp-stale" }));
        expect(await screen.findByText("Not Generated")).toBeInTheDocument();
        expect(screen.queryByRole("button", { name: "Launch" })).not.toBeInTheDocument();
    });

    test("clicking expand/collapse toggles details independently for each process", async () => {
        listModelSummaries.mockResolvedValue([
            { id: "m-1", name: "Proc 1", projectId: 5, projectDisplayName: "Proj 1" },
            { id: "m-2", name: "Proc 2", projectId: 6, projectDisplayName: "Proj 2" },
        ]);
        getWorkflowState.mockImplementation(async (id) => ({
            stages: { GENERATE: { status: "COMPLETED", detail: `gp-${id}` } },
        }));
        listRunningProjects.mockResolvedValue([
            { projectId: "gp-m-1", modelId: "m-1", port: 8091, processKey: "proc1" },
            { projectId: "gp-m-2", modelId: "m-2", port: 8092, processKey: "proc2" },
        ]);

        render(<LaunchProjectListPage />);
        await screen.findByText("Proc 1");

        // Details collapsed initially
        expect(screen.queryByText("8091")).not.toBeInTheDocument();
        expect(screen.queryByText("8092")).not.toBeInTheDocument();

        const expandBtns = screen.getAllByRole("button", { name: "Expand details" });
        expect(expandBtns).toHaveLength(2);

        // Expand first process details only
        userEvent.click(expandBtns[0]);
        expect(screen.getByText("8091")).toBeInTheDocument();
        expect(screen.queryByText("8092")).not.toBeInTheDocument();

        // Collapse first process details
        const collapseBtn = screen.getByRole("button", { name: "Collapse details" });
        userEvent.click(collapseBtn);
        expect(screen.queryByText("8091")).not.toBeInTheDocument();
    });

    test("a launch failure never attempts to pair", async () => {
        listModelSummaries.mockResolvedValue([
            { id: "m-1", name: "Wire Transfer Review", projectId: 5, projectDisplayName: "RedCollar Suits" },
        ]);
        getWorkflowState.mockResolvedValue({ stages: { GENERATE: { status: "COMPLETED", detail: "gp-1" } } });
        launchProject.mockRejectedValue(new Error("Boom"));

        render(<LaunchProjectListPage />);
        await screen.findByText("Wire Transfer Review");

        userEvent.click(button("Launch"));

        expect(await screen.findByText(/Launch failed: Boom/)).toBeInTheDocument();
        expect(global.fetch).not.toHaveBeenCalled();
    });
});
