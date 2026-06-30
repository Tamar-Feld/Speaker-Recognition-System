import { BrowserRouter, Routes, Route } from 'react-router-dom'
import { AdminAuthProvider, ProtectedRoute } from './context/AdminAuthContext.jsx'
import CorridorPage from './pages/CorridorPage.jsx'
import DoorPage     from './pages/DoorPage.jsx'
import LoginPage    from './pages/LoginPage.jsx'
import AdminPage    from './pages/AdminPage.jsx'

export default function App () {
  return (
    <AdminAuthProvider>
      <BrowserRouter>
        <Routes>
          <Route path="/"         element={<CorridorPage />} />
          <Route path="/door/:id" element={<DoorPage />} />
          <Route path="/login"    element={<LoginPage />} />
          <Route path="/admin"    element={<ProtectedRoute><AdminPage /></ProtectedRoute>} />
        </Routes>
      </BrowserRouter>
    </AdminAuthProvider>
  )
}
