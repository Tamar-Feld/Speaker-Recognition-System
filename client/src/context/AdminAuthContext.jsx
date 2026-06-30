import { createContext, useContext, useState, useCallback } from 'react'
import { Navigate } from 'react-router-dom'
import { loginAdmin } from '../services/api'

const TOKEN_KEY = 'adminToken'

const AdminAuthContext = createContext(null)

export function AdminAuthProvider ({ children }) {
  // Derive initial auth state from the persisted token.
  // sessionStorage is cleared when the browser tab closes, matching
  // the existing "lost on refresh in incognito" behaviour documented in CLAUDE.md.
  const [isAuthenticated, setIsAuthenticated] = useState(
    () => !!sessionStorage.getItem(TOKEN_KEY)
  )

  /**
   * Calls POST /api/admin/login, stores the returned JWT, and updates state.
   * Returns { success: true } on success or { success: false, message } on failure.
   */
  const login = useCallback(async (username, password) => {
    try {
      const { data } = await loginAdmin(username, password)
      if (data.success && data.token) {
        sessionStorage.setItem(TOKEN_KEY, data.token)
        setIsAuthenticated(true)
        return { success: true }
      }
      return { success: false, message: data.message || 'שגיאת אימות' }
    } catch (err) {
      const message =
        err.response?.data?.message || 'שם משתמש או סיסמה שגויים'
      return { success: false, message }
    }
  }, [])

  const logout = useCallback(() => {
    sessionStorage.removeItem(TOKEN_KEY)
    setIsAuthenticated(false)
  }, [])

  return (
    <AdminAuthContext.Provider value={{ isAuthenticated, login, logout }}>
      {children}
    </AdminAuthContext.Provider>
  )
}

export function useAdminAuthContext () {
  return useContext(AdminAuthContext)
}

export function ProtectedRoute ({ children }) {
  const { isAuthenticated } = useAdminAuthContext()
  return isAuthenticated ? children : <Navigate to="/login" replace />
}
