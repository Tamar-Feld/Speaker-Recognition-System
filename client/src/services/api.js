import axios from 'axios'

const BASE = 'http://localhost:8080/api'

// Axios instance shared by all protected endpoints.
// The request interceptor reads the JWT from sessionStorage and attaches it
// automatically — callers don't need to know about tokens at all.
const apiClient = axios.create({ baseURL: BASE })

apiClient.interceptors.request.use((config) => {
  const token = sessionStorage.getItem('adminToken')
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

// POST /api/admin/login  → { success, token, message }
export const loginAdmin = (username, password) =>
  apiClient.post('/admin/login', { username, password })

// POST /api/audio/upload  (public — no JWT required, but interceptor is harmless)
// → { accessGranted, identifiedSpeaker, confidence (0–1), message }
export const uploadAudio = (file, roomId) => {
  const fd = new FormData()
  fd.append('file', file, 'recording.webm')
  fd.append('roomId', roomId)
  return apiClient.post('/audio/upload', fd)
}

// POST /api/users/register  (multipart: username, fullName, samples[])
export const registerUser = (formData) => apiClient.post('/users/register', formData)

// GET /api/users  → [{ id, username, fullName, authorized, createdAt }]
export const fetchUsers = () => apiClient.get('/users')

// PUT /api/users/:id/toggle  — flips isAuthorized
export const toggleUser = (id) => apiClient.put(`/users/${id}/toggle`)

// GET /api/users/:username/rooms  → [roomNumber, ...]
export const fetchUserRooms = (username) => apiClient.get(`/users/${username}/rooms`)

// POST /api/users/:username/rooms/:roomNum  — toggles a single room permission
export const toggleUserRoom = (username, roomNum) =>
  apiClient.post(`/users/${username}/rooms/${roomNum}`)

// GET /api/logs  → [{ id, filename, timestamp, identifiedSpeaker, confidence, roomNumber, accessGranted }]
export const fetchLogs = () => apiClient.get('/logs')
