import React from 'react'
import { useLocation, Outlet, Navigate} from 'react-router-dom';

const ProtectedRoute = ({ children, allowedRoles = [], useOutlet = false }) => {
    const isAuthenticated = localStorage.getItem("authToken");
    const location = useLocation();

    if (!isAuthenticated) {
       // Redirect to login and remember the last location add a path for '/login' that invokes the keycloak login (the code below is not going to work)
       return <Navigate to='/login' state={{ from: location }} replace />;
    } else {
      return useOutlet ? <Outlet /> : children;
    }
};

export default ProtectedRoute;