import { api } from "../../components/config/api";
import {
    getActivityExecutionState,
    listTwinProcesses,
    loadExecutionEvidence,
} from "./WorkbenchService";

// Mocks verify the API response envelope and runtime execution status mapping.
jest.mock("../../components/config/api", () => ({
    api: { get: jest.fn(), post: jest.fn() },
}));

const executionUrl = (twinId, activityId) =>
    `/wb/transmute/twin/${encodeURIComponent(twinId)}/activity/${encodeURIComponent(activityId)}/execution`;

describe("WorkbenchService - runtime execution evidence", () => {
    beforeEach(() => jest.clearAllMocks());

    test("listTwinProcesses reuses the existing twins endpoint with modelId as a query param", async () => {
        api.get.mockResolvedValue({ data: [{ id: "t-1" }] });

        const result = await listTwinProcesses("m-1");

        expect(api.get).toHaveBeenCalledWith("/wb/transmute/twins", { params: { modelId: "m-1" } });
        expect(result).toEqual([{ id: "t-1" }]);
    });

    test("getActivityExecutionState hits the same endpoint the VS Code extension consumes", async () => {
        api.get.mockResolvedValue({ data: { activityId: "A", status: "EXECUTED" } });

        const result = await getActivityExecutionState("t-1", "A");

        expect(api.get).toHaveBeenCalledWith(executionUrl("t-1", "A"));
        expect(result).toEqual({ activityId: "A", status: "EXECUTED" });
    });

    test("getActivityExecutionState encodes ids so BPMN activity ids are URL-safe", async () => {
        api.get.mockResolvedValue({ data: {} });

        await getActivityExecutionState("t 1", "_BF94795D-C812/42FC");

        expect(api.get).toHaveBeenCalledWith(executionUrl("t 1", "_BF94795D-C812/42FC"));
    });

    test("loadExecutionEvidence returns runtime state verbatim per twin activity", async () => {
        const output = { executor: "DataEnricherExecutor", enrichedTier: "PREMIUM_CORPORATE", enrichedAt: "2026-09-03T08:33:33Z" };
        api.get.mockImplementation((url) => {
            if (url === "/wb/transmute/twins") {
                return Promise.resolve({
                    data: {
                        message: "Success!",
                        data: [
                            { id: "t-1", status: "RUNNING", launchedAt: "L1", activityLinks: [{ originalActivityId: "A", twinActivityId: "A" }] },
                        ],
                    },
                });
            }
            return Promise.resolve({
                data: { message: "Success!", data: { activityId: "A", agentName: "data-enricher-agent-01", status: "EXECUTED", summary: "s", output } },
            });
        });

        const result = await loadExecutionEvidence("m-1");

        expect(result).toHaveLength(1);
        expect(result[0].twinId).toBe("t-1");
        expect(result[0].activities).toHaveLength(1);
        expect(result[0].activities[0].state.status).toBe("EXECUTED");
        expect(result[0].activities[0].state.agentName).toBe("data-enricher-agent-01");
        expect(result[0].activities[0].state.output).toEqual(output);
        expect(result[0].activities[0].error).toBeNull();
    });

    test("loadExecutionEvidence skips twins with no connected activities", async () => {
        api.get.mockImplementation((url) => {
            if (url === "/wb/transmute/twins") {
                return Promise.resolve({ data: { message: "Success!", data: [{ id: "t-empty", activityLinks: [] }, { id: "t-none" }] } });
            }
            return Promise.resolve({ data: {} });
        });

        const result = await loadExecutionEvidence("m-1");

        expect(result).toEqual([]);
        // no execution call should be made for a twin that has nothing connected
        expect(api.get).toHaveBeenCalledTimes(1);
    });

    test("loadExecutionEvidence represents multiple twins and multiple activities", async () => {
        api.get.mockImplementation((url) => {
            if (url === "/wb/transmute/twins") {
                return Promise.resolve({
                    data: {
                        message: "Success!",
                        data: [
                            { id: "t-1", activityLinks: [{ originalActivityId: "A" }, { originalActivityId: "B" }] },
                            { id: "t-2", activityLinks: [{ originalActivityId: "C" }] },
                        ],
                    },
                });
            }
            return Promise.resolve({ data: { message: "Success!", data: { status: "BOUND" } } });
        });

        const result = await loadExecutionEvidence("m-1");

        expect(result.map((t) => t.twinId)).toEqual(["t-1", "t-2"]);
        expect(result[0].activities.map((a) => a.activityId)).toEqual(["A", "B"]);
        expect(result[1].activities.map((a) => a.activityId)).toEqual(["C"]);
    });

    test("one unreadable activity is captured without losing the others", async () => {
        api.get.mockImplementation((url) => {
            if (url === "/wb/transmute/twins") {
                return Promise.resolve({
                    data: { message: "Success!", data: [{ id: "t-1", activityLinks: [{ originalActivityId: "A" }, { originalActivityId: "B" }] }] },
                });
            }
            if (url === executionUrl("t-1", "A")) return Promise.reject(new Error("boom"));
            return Promise.resolve({ data: { message: "Success!", data: { status: "EXECUTED" } } });
        });

        const result = await loadExecutionEvidence("m-1");

        expect(result[0].activities[0]).toEqual({ activityId: "A", state: null, error: "unavailable" });
        expect(result[0].activities[1].state.status).toBe("EXECUTED");
    });

    test("a failing twins lookup propagates so the UI can show unavailable rather than empty", async () => {
        api.get.mockRejectedValue(new Error("network"));

        await expect(loadExecutionEvidence("m-1")).rejects.toThrow("network");
    });
});
