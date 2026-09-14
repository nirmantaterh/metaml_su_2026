import React, { useState, useEffect } from "react";
import { FontAwesomeIcon } from "@fortawesome/react-fontawesome";
import { faUser, faHouse } from "@fortawesome/free-solid-svg-icons";
import { Navbar, Container, Nav, NavDropdown, DropdownButton } from "react-bootstrap";
import { Link, useNavigate, useLocation } from "react-router-dom";

import { WorkbenchRoutes } from "../../routes";
import UseMessageAlerts from "../hooks/UseMessageAlerts";
import AlertMessage from "../common/AlertMessage";

const Header = () => {
    const { errorMessage, setErrorMessage, showErrorAlert, setShowErrorAlert } = UseMessageAlerts();
    const navigate = useNavigate();
    const location = useLocation();
    const from = location.state?.from?.pathname || "/";

    return (
        <Navbar expand="lg" sticky='top' className="navbar navbar-light fixed-top py-1 navbar-expand-xl navbar-custom shadow">
            <Container>
                <Navbar.Brand to={"/"} as={Link} className='nav-home' style={{ textDecoration: 'none'}}>
                    {<FontAwesomeIcon icon={faHouse} />}
                </Navbar.Brand>
                <Navbar.Toggle aria-controls='responsive-navbar-nav'/>
                <Navbar.Collapse id="basic-navbar-nav">
                    <Nav className="me-auto">
                        <Nav.Link to={"/"}>Catalog</Nav.Link>
                        <NavDropdown title="Project" id="project-nav-dropdown">
                            <NavDropdown.Item as={Link} to={WorkbenchRoutes.CreateProject.path}>
                                Create
                            </NavDropdown.Item>
                            <NavDropdown.Item as={Link} to={WorkbenchRoutes.ProjectList.path}>
                                Edit
                            </NavDropdown.Item>
                            <NavDropdown.Item as={Link} to={WorkbenchRoutes.DeleteProject.path}>
                                Delete
                            </NavDropdown.Item>
                        </NavDropdown>
                        {/* Model / Generate / Launch is one pipeline (see WorkflowStage) but three separate pickers, not one editor with three buttons: Model resumes the model last opened or saved in the editor (blank if there is none - see ModelPage's resume redirect, and its "New model" button for an explicit blank canvas); Generate and Launch each list every saved process across every project and act on whichever row you pick - see GenerateProjectListPage / LaunchProjectListPage. */}
                        <NavDropdown title="Transmute" id="transmute-nav-dropdown">
                            <NavDropdown.Item as={Link} to={WorkbenchRoutes.ModelPage.path}>
                                Model
                            </NavDropdown.Item>
                            <NavDropdown.Item as={Link} to={WorkbenchRoutes.GenerateModelList.path}>
                                Generate
                            </NavDropdown.Item>
                            <NavDropdown.Item as={Link} to={WorkbenchRoutes.LaunchModelList.path}>
                                Launch
                            </NavDropdown.Item>
                        </NavDropdown>
                        {/* Two different things share this menu: the twin workflow (Connect/Evolve/Bridge) and Evolve Workflow itself - connecting to a deployed generated application, not a twin */}
                        <NavDropdown title="Evolve" id="evolve-nav-dropdown">
                            <NavDropdown.Item as={Link} to={WorkbenchRoutes.EvolvePage.path}>
                                Twin Workflow
                            </NavDropdown.Item>
                            <NavDropdown.Item as={Link} to={WorkbenchRoutes.DeployedAppsPage.path}>
                                Deployed Applications
                            </NavDropdown.Item>
                        </NavDropdown>
                        {/* Tenant policy lifecycle plus its approvals - unrelated to Evolve above, which is twin execution, not policy */}
                        <NavDropdown title="Governance" id="governance-nav-dropdown">
                            <NavDropdown.Item as={Link} to={WorkbenchRoutes.GovernancePolicies.path}>
                                Policies
                            </NavDropdown.Item>
                            <NavDropdown.Item as={Link} to={WorkbenchRoutes.GovernanceApprovals.path}>
                                Approvals
                            </NavDropdown.Item>
                        </NavDropdown>
                        <Nav.Link to={"/"}>Help</Nav.Link>
                    </Nav>
                    {/* <span class="ml-auto navbar-text"></span> */}
                    <Nav className="justify-content-end">
                        {/* <Nav.Link to={"/"}>About</Nav.Link> */}
                        {/* <Nav.Link to={"/"}>Contact</Nav.Link> */}
                    </Nav>
                </Navbar.Collapse>
            </Container>
        </Navbar>
    );
};

export default Header;
