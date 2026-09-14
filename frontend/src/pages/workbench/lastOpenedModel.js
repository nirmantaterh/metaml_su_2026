// Which model the editor should come back to when Transmute > Model is opened without an id. Written whenever ModelPage loads or saves a model; read by the resume redirect in ModelPage. The project id rides along because a ProcessModel doesn't carry one (see the summaries fallback in ModelPage), and the editor needs it for the project picker and "Back to project processes".
const KEY = "metaml.workbench.lastOpenedModel";

export function readLastOpenedModel() {
    try {
        const raw = window.localStorage.getItem(KEY);
        const parsed = raw ? JSON.parse(raw) : null;
        return parsed && parsed.id ? parsed : null;
    } catch (e) {
        return null;
    }
}

export function rememberLastOpenedModel(id, projectId) {
    try {
        window.localStorage.setItem(KEY, JSON.stringify({ id, projectId: projectId || null }));
    } catch (e) {
        // storage unavailable (private mode, quota) - resume just won't work, nothing else breaks
    }
}

export function forgetLastOpenedModel(id) {
    const current = readLastOpenedModel();
    if (current && current.id === id) {
        try {
            window.localStorage.removeItem(KEY);
        } catch (e) {
            // ignore
        }
    }
}
