import React from "react";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";

import GenerateProjectListPage from "./GenerateProjectListPage";
import { listModelSummaries, generateProject } from "../../services/workbench/WorkbenchService";

jest.mock("../../services/workbench/WorkbenchService", () => ({
    listModelSummaries: jest.fn(),
    generateProject: jest.fn(),
}));

const button = (name) => screen.getByRole("button", { name });

describe("GenerateProjectListPage", () => {
    beforeEach(() => {
        jest.clearAllMocks();
    });

    test("lists every saved process with its project, and generates the row that's clicked", async () => {
        listModelSummaries.mockResolvedValue([
            { id: "m-1", name: "Wire Transfer Review", projectId: 5, projectDisplayName: "RedCollar Suits" },
            { id: "m-2", name: "Loan Approval", projectId: 6, projectDisplayName: "Loans" },
        ]);
        generateProject.mockResolvedValue({ projectId: "gp-1", processKey: "wireTransferReview" });

        render(<GenerateProjectListPage />);

        expect(await screen.findByText("Wire Transfer Review")).toBeInTheDocument();
        expect(screen.getByText("Loan Approval")).toBeInTheDocument();
        expect(screen.getByText("RedCollar Suits (5)")).toBeInTheDocument();

        const generateButtons = screen.getAllByRole("button", { name: "Generate" });
        userEvent.click(generateButtons[0]);

        await waitFor(() => expect(generateProject).toHaveBeenCalledWith({ modelId: "m-1" }));
        expect(await screen.findByText(/Generate successful \("wireTransferReview"\)/))
            .toBeInTheDocument();
    });

    test("reports a failed generate on just that row", async () => {
        listModelSummaries.mockResolvedValue([
            { id: "m-1", name: "Wire Transfer Review", projectId: 5, projectDisplayName: "RedCollar Suits" },
        ]);
        generateProject.mockRejectedValue(new Error("Kaboom"));

        render(<GenerateProjectListPage />);
        await screen.findByText("Wire Transfer Review");

        userEvent.click(button("Generate"));

        expect(await screen.findByText(/Generate failed: Kaboom/)).toBeInTheDocument();
    });

    test("shows an empty state when nothing has been saved yet", async () => {
        listModelSummaries.mockResolvedValue([]);

        render(<GenerateProjectListPage />);

        expect(await screen.findByText(/Nothing saved yet/)).toBeInTheDocument();
    });
});
