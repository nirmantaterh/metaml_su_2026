import React from "react";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";

import GenerateProjectListPage from "./GenerateProjectListPage";
import { listModelSummaries, generateProject, getWorkflowState } from "../../services/workbench/WorkbenchService";

jest.mock("../../services/workbench/WorkbenchService", () => ({
    listModelSummaries: jest.fn(),
    generateProject: jest.fn(),
    getWorkflowState: jest.fn(),
}));

const NOT_GENERATED = { currentStage: "MODEL", stages: { MODEL: { status: "COMPLETED" } } };
const GENERATED = {
    currentStage: "GENERATE",
    stages: { MODEL: { status: "COMPLETED" }, GENERATE: { status: "COMPLETED", detail: "gp-1", timestamp: "2026-09-14T10:00:00Z" } },
};

const button = (name) => screen.getByRole("button", { name });
// rows link into the editor, which needs a router around the page
const renderPage = () => render(<MemoryRouter><GenerateProjectListPage /></MemoryRouter>);

describe("GenerateProjectListPage", () => {
    beforeEach(() => {
        jest.clearAllMocks();
        getWorkflowState.mockResolvedValue(NOT_GENERATED);
    });

    test("lists every saved process with its project, and generates the row that's clicked", async () => {
        listModelSummaries.mockResolvedValue([
            { id: "123e4567-e89b-12d3-a456-426614174000", name: "Wire Transfer Review", projectId: 5, projectDisplayName: "RedCollar Suits" },
            { id: "m-2", name: "Loan Approval", projectId: 6, projectDisplayName: "Loans" },
        ]);
        generateProject.mockResolvedValue({ projectId: "gp-1", processKey: "wireTransferReview" });

        renderPage();

        expect(await screen.findByText("Wire Transfer Review")).toBeInTheDocument();
        expect(screen.getByText("Loan Approval")).toBeInTheDocument();
        expect(screen.getByText("RedCollar Suits (5)")).toBeInTheDocument();
        expect(screen.queryByText("123e4567-e89b-12d3-a456-426614174000")).not.toBeInTheDocument();

        const generateButtons = screen.getAllByRole("button", { name: "Generate" });
        userEvent.click(generateButtons[0]);

        await waitFor(() => expect(generateProject).toHaveBeenCalledWith({ modelId: "123e4567-e89b-12d3-a456-426614174000" }));
        expect(await screen.findByText(/Generate successful \("wireTransferReview"\)/))
            .toBeInTheDocument();
    });

    // The state comes from the same GENERATE stage ModelPage and the Launch picker read, so generating from the editor is reflected here on arrival - the row must not pretend it still needs generating.
    test("a process already generated elsewhere shows as Generated with a Regenerate button", async () => {
        listModelSummaries.mockResolvedValue([
            { id: "m-1", name: "Wire Transfer Review", projectId: 5, projectDisplayName: "RedCollar Suits" },
            { id: "m-2", name: "Loan Approval", projectId: 6, projectDisplayName: "Loans" },
        ]);
        getWorkflowState.mockImplementation(async (id) => (id === "m-1" ? GENERATED : NOT_GENERATED));

        renderPage();
        await screen.findByText("Wire Transfer Review");

        expect(screen.getByText("Generated")).toBeInTheDocument();
        expect(screen.getByText("Not Generated")).toBeInTheDocument();
        expect(screen.getByRole("button", { name: "Regenerate" })).toBeInTheDocument();
        expect(screen.getByRole("button", { name: "Generate" })).toBeInTheDocument();
    });

    test("a row flips to Generated right after generating it here", async () => {
        listModelSummaries.mockResolvedValue([
            { id: "m-1", name: "Wire Transfer Review", projectId: 5, projectDisplayName: "RedCollar Suits" },
        ]);
        generateProject.mockResolvedValue({ projectId: "gp-1", processKey: "wireTransferReview" });

        renderPage();
        await screen.findByText("Wire Transfer Review");
        expect(screen.getByText("Not Generated")).toBeInTheDocument();

        getWorkflowState.mockResolvedValue(GENERATED);
        userEvent.click(button("Generate"));

        expect(await screen.findByText("Generated")).toBeInTheDocument();
        expect(button("Regenerate")).toBeInTheDocument();
    });

    test("reports a failed generate on just that row", async () => {
        listModelSummaries.mockResolvedValue([
            { id: "m-1", name: "Wire Transfer Review", projectId: 5, projectDisplayName: "RedCollar Suits" },
        ]);
        generateProject.mockRejectedValue(new Error("Kaboom"));

        renderPage();
        await screen.findByText("Wire Transfer Review");

        userEvent.click(button("Generate"));

        expect(await screen.findByText(/Generate failed: Kaboom/)).toBeInTheDocument();
    });

    test("the process name links into the editor for that model, carrying its project", async () => {
        listModelSummaries.mockResolvedValue([
            { id: "m-1", name: "Wire Transfer Review", projectId: 5, projectDisplayName: "RedCollar Suits" },
        ]);

        renderPage();

        const link = await screen.findByRole("link", { name: "Wire Transfer Review" });
        expect(link).toHaveAttribute("href", "/wb/model/m-1");
    });

    test("shows an empty state when nothing has been saved yet", async () => {
        listModelSummaries.mockResolvedValue([]);

        renderPage();

        expect(await screen.findByText(/Nothing saved yet/)).toBeInTheDocument();
    });
});
