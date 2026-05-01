import { Navigate, Route, Routes } from 'react-router-dom';
import { LoginPage } from './pages/LoginPage';
import { WorkflowsPage } from './pages/WorkflowsPage';
import { WorkflowDetailsPage } from './pages/WorkflowDetailsPage';
import { ProtectedRoute } from './components/ProtectedRoute';

export default function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route element={<ProtectedRoute />}>
        <Route path="/workflows"     element={<WorkflowsPage />} />
        <Route path="/workflows/:id" element={<WorkflowDetailsPage />} />
        <Route path="*"              element={<Navigate to="/workflows" replace />} />
      </Route>
    </Routes>
  );
}
