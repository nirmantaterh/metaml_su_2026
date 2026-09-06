import React from "react";
import {
    Route,
    RouterProvider,
    createBrowserRouter,
    createRoutesFromElements
} from "react-router-dom";

import RootLayout from "./components/layout/RootLayout";
import Home from "./pages/Home";
import SamplPage from "./pages/workbench/SamplePage";
import ModelPage from "./pages/workbench/ModelPage";
import EditProjectListPage from "./pages/workbench/EditProjectListPage";
import GenerateProjectListPage from "./pages/workbench/GenerateProjectListPage";
import LaunchProjectListPage from "./pages/workbench/LaunchProjectListPage";
import CreateProjectPage from "./pages/project/CreateProjectPage";
import ProjectListPage from "./pages/project/ProjectListPage";
import ProjectProcessListPage from "./pages/project/ProjectProcessListPage";
import DeleteProjectPage from "./pages/project/DeleteProjectPage";
import EvolvePage from "./pages/workbench/EvolvePage";
import DeployedAppsPage from "./pages/workbench/DeployedAppsPage";
import GovernancePoliciesPage from "./pages/workbench/GovernancePoliciesPage";
import GovernanceApprovalsPage from "./pages/workbench/GovernanceApprovalsPage";
import { CommonRoutes, WorkbenchRoutes } from "./routes";

const router = createBrowserRouter(
    createRoutesFromElements(
        <>
            <Route path={CommonRoutes.Home.path} element={<RootLayout />}>
                <Route index element={<Home />}/>

                <Route path={WorkbenchRoutes.SamplePage.path} element={<SamplPage/>} />
                {/* legacy path, still works if bookmarked - starts blank, same as CreateModel */}
                <Route path={WorkbenchRoutes.ModelPage.path} element={<ModelPage/>} />
                <Route path={WorkbenchRoutes.CreateModel.path} element={<ModelPage/>} />
                <Route path={WorkbenchRoutes.ModelEditor.path} element={<ModelPage/>} />
                <Route path={WorkbenchRoutes.EditModel.path} element={<EditProjectListPage/>} />
                <Route path={WorkbenchRoutes.GenerateModelList.path} element={<GenerateProjectListPage/>} />
                <Route path={WorkbenchRoutes.LaunchModelList.path} element={<LaunchProjectListPage/>} />
                <Route path={WorkbenchRoutes.CreateProject.path} element={<CreateProjectPage/>} />
                <Route path={WorkbenchRoutes.ProjectList.path} element={<ProjectListPage/>} />
                <Route path={WorkbenchRoutes.ProjectProcesses.path} element={<ProjectProcessListPage/>} />
                <Route path={WorkbenchRoutes.DeleteProject.path} element={<DeleteProjectPage/>} />
                <Route path={WorkbenchRoutes.EvolvePage.path} element={<EvolvePage/>} />
                <Route path={WorkbenchRoutes.DeployedAppsPage.path} element={<DeployedAppsPage/>} />
                <Route path={WorkbenchRoutes.GovernancePolicies.path} element={<GovernancePoliciesPage/>} />
                <Route path={WorkbenchRoutes.GovernanceApprovals.path} element={<GovernanceApprovalsPage/>} />
            </Route>
        </>
    )
)

export default function Navigation() {
    return (
        <>
            <RouterProvider router={router}/>
        </>
    );
}
