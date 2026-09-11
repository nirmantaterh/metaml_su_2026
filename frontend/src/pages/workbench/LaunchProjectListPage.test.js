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
        expect(screen.queryByRole("button", { name: "Go to Target Platform ↗" })).not.toBeInTheDocument();
    });

    test("Launch starts platform, auto-opens Cockpit, sets Running status with Open button, revealing Stop after Open click", async () => {
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
        expect(openCockpitUrl).toHaveBeenCalledWith("http://localhost:8091/camunda/app/cockpit/engine/");

        // Running status visible
        expect(screen.getByText("Running")).toBeInTheDocument();

        // Runtime details are collapsed by default
        expect(screen.queryByText("8091")).not.toBeInTheDocument();
        expect(screen.queryByText("Started")).not.toBeInTheDocument();

        // Expanding details reveals engine and Target Platform endpoints.
        const expandBtn = screen.getByRole("button", { name: "Expand details" });
        userEvent.click(expandBtn);
        expect(screen.getByText("8091")).toBeInTheDocument();
        expect(screen.getByText("Started")).toBeInTheDocument();
        expect(screen.getByText("https://tp.acme.internal/metaml")).toBeInTheDocument();
        const targetPlatformButton = screen.getByRole("button", { name: "Go to Target Platform ↗" });
        userEvent.click(targetPlatformButton);
        expect(openCockpitUrl).toHaveBeenCalledWith("https://tp.acme.internal/metaml");

        // Initially shows Open button, Stop is NOT shown yet
        const openBtn = screen.getByRole("button", { name: "Open" });
        expect(openBtn).toBeInTheDocument();
        expect(screen.queryByRole("button", { name: "Stop" })).not.toBeInTheDocument();

        // Clicking Open re-opens Cockpit and reveals Stop button
        userEvent.click(openBtn);
        expect(openCockpitUrl).toHaveBeenCalledTimes(3);
        expect(screen.getByRole("button", { name: "Stop" })).toBeInTheDocument();
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

        // Click Open to expose Stop
        const openBtn = await screen.findByRole("button", { name: "Open" });
        userEvent.click(openBtn);

        const stopBtn = screen.getByRole("button", { name: "Stop" });
        userEvent.click(stopBtn);

        await waitFor(() => expect(stopProject).toHaveBeenCalledWith({ projectId: "gp-1" }));
        expect(await screen.findByText("Generated / Stopped")).toBeInTheDocument();
        expect(screen.getByRole("button", { name: "Launch" })).toBeInTheDocument();
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
