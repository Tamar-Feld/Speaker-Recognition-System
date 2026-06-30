// AdminPage.jsx — SpeakKey Admin Dashboard
// ─────────────────────────────────────────────────────────────────────────────
// User JSON:  { id, username, fullName, authorized, createdAt }
//             username   = login ID   (e.g. "tamar")
//             fullName   = display name (e.g. "תמר כהן")
//             authorized = boolean — Java's isAuthorized() getter is
//                          serialized by Jackson as "authorized" in the JSON,
//                          NOT "isAuthorized" — read it as u.authorized.
//
// Log JSON:   { id, filename, timestamp, identifiedSpeaker,
//               confidence (double, 0-1), roomNumber, accessGranted }
//
// NOTE on the React.Fragment fix below: each row used to render a bare
// `<>...</>` fragment with `key` placed on the inner <tr> instead of the
// fragment itself. React requires the key on the element returned directly
// from .map(), so this now uses `<React.Fragment key={u.id}>` explicitly.
// ─────────────────────────────────────────────────────────────────────────────

import React, { useState, useEffect, useCallback } from 'react'
import { useNavigate } from 'react-router-dom'
import { useAdminAuthContext } from '../context/AdminAuthContext.jsx'
import {
  fetchUsers, toggleUser, registerUser,
  fetchUserRooms, toggleUserRoom, fetchLogs,
} from '../services/api'

const ROOM_NUMS = [1, 2, 3, 4, 5, 6]

// confidence is stored/transmitted as 0-1 everywhere — this is the one place
// it gets turned into a percentage for display.
const fmtConf = (c) => Math.round((c || 0) * 100) + '%'

export default function AdminPage () {
  const { logout } = useAdminAuthContext()
  const navigate = useNavigate()

  const [tab, setTab]   = useState('users')   // users | logs
  const [users, setUsers] = useState([])
  const [logs, setLogs]   = useState([])
  const [userRooms, setUserRooms] = useState({})   // { [username]: [roomNum, ...] }
  const [loading, setLoading] = useState(true)
  const [errMsg, setErrMsg]   = useState('')

  // ── register-employee form state ──────────────────────────────────────
  const [regUsername, setRegUsername] = useState('')
  const [regFullName, setRegFullName] = useState('')
  const [regFiles, setRegFiles]       = useState([])
  const [regBusy, setRegBusy]         = useState(false)

  const loadAll = useCallback(async () => {
    setLoading(true)
    setErrMsg('')
    try {
      const [{ data: u }, { data: l }] = await Promise.all([fetchUsers(), fetchLogs()])
      setUsers(u)
      setLogs(l)

      const roomsByUser = {}
      await Promise.all(
        u.map(async (usr) => {
          try {
            const { data: rooms } = await fetchUserRooms(usr.username)
            roomsByUser[usr.username] = rooms
          } catch {
            roomsByUser[usr.username] = []
          }
        })
      )
      setUserRooms(roomsByUser)
    } catch (e) {
      setErrMsg('שגיאה בטעינת נתונים מהשרת (Spring Boot למאזין בפורט 8080?)')
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => { loadAll() }, [loadAll])

  async function handleToggleUser (id) {
    await toggleUser(id)
    loadAll()
  }

  async function handleToggleRoom (username, roomNum) {
    await toggleUserRoom(username, roomNum)
    setUserRooms((prev) => {
      const cur = prev[username] || []
      const next = cur.includes(roomNum) ? cur.filter((r) => r !== roomNum) : [...cur, roomNum]
      return { ...prev, [username]: next }
    })
  }

  async function handleRegister (e) {
    e.preventDefault()
    if (!regUsername || !regFullName || regFiles.length < 3) {
      setErrMsg('נדרשים שם משתמש, שם מלא ולפחות 3 הקלטות קוליות')
      return
    }
    setRegBusy(true)
    try {
      const fd = new FormData()
      fd.append('username', regUsername)
      fd.append('fullName', regFullName)
      regFiles.forEach((f) => fd.append('samples', f))
      await registerUser(fd)
      setRegUsername(''); setRegFullName(''); setRegFiles([])
      loadAll()
    } catch {
      setErrMsg('שגיאה ברישום משתמש — ייתכן ששם המשתמש כבר קיים')
    } finally {
      setRegBusy(false)
    }
  }

  function handleLogout () {
    logout()
    navigate('/login')
  }

  return (
    <>
      <style>{CSS}</style>
      <div className="ap-root">
        <header className="ap-header">
          <div className="ap-brand">SPEAKKEY · ADMIN</div>
          <nav className="ap-tabs">
            <button className={`ap-tab ${tab === 'users' ? 'active' : ''}`} onClick={() => setTab('users')}>
              עובדים והרשאות
            </button>
            <button className={`ap-tab ${tab === 'logs' ? 'active' : ''}`} onClick={() => setTab('logs')}>
              יומן גישות
            </button>
          </nav>
          <button className="ap-logout" onClick={handleLogout}>התנתקות</button>
        </header>

        {errMsg && <div className="ap-err">{errMsg}</div>}

        {loading ? (
          <div className="ap-loading">טוען נתונים…</div>
        ) : tab === 'users' ? (
          <div className="ap-content">
            <section className="ap-card">
              <h2>רישום עובד חדש</h2>
              <form className="ap-reg-form" onSubmit={handleRegister}>
                <input
                  className="ap-input"
                  placeholder="שם משתמש (login ID)"
                  value={regUsername}
                  onChange={(e) => setRegUsername(e.target.value)}
                />
                <input
                  className="ap-input"
                  placeholder="שם מלא"
                  value={regFullName}
                  onChange={(e) => setRegFullName(e.target.value)}
                />
                <input
                  className="ap-input ap-file"
                  type="file"
                  accept="audio/*"
                  multiple
                  onChange={(e) => setRegFiles(Array.from(e.target.files))}
                />
                <button className="ap-btn" type="submit" disabled={regBusy}>
                  {regBusy ? 'רושם…' : 'רישום'}
                </button>
              </form>
            </section>

            <section className="ap-card">
              <h2>עובדים והרשאות חדרים</h2>
              <table className="ap-table">
                <thead>
                  <tr>
                    <th>שם מלא</th>
                    <th>שם משתמש</th>
                    <th>מאושר</th>
                    {ROOM_NUMS.map((r) => <th key={r}>חדר {r}</th>)}
                  </tr>
                </thead>
                <tbody>
                  {users.map((u) => (
                    <React.Fragment key={u.id}>
                      <tr className="ap-row">
                        <td>{u.fullName}</td>
                        <td>{u.username}</td>
                        <td>
                          <button
                            className={`ap-pill ${u.authorized ? 'on' : 'off'}`}
                            onClick={() => handleToggleUser(u.id)}
                          >
                            {u.authorized ? 'מאושר' : 'חסום'}
                          </button>
                        </td>
                        {ROOM_NUMS.map((r) => (
                          <td key={r} className="ap-room-cell">
                            <input
                              type="checkbox"
                              checked={(userRooms[u.username] || []).includes(r)}
                              onChange={() => handleToggleRoom(u.username, r)}
                            />
                          </td>
                        ))}
                      </tr>
                    </React.Fragment>
                  ))}
                </tbody>
              </table>
            </section>
          </div>
        ) : (
          <div className="ap-content">
            <section className="ap-card">
              <h2>יומן גישות חי</h2>
              <table className="ap-table">
                <thead>
                  <tr>
                    <th>שעה</th>
                    <th>חדר</th>
                    <th>מזוהה</th>
                    <th>ביטחון</th>
                    <th>תוצאה</th>
                  </tr>
                </thead>
                <tbody>
                  {logs.map((log) => (
                    <tr key={log.id} className="ap-row">
                      <td>{new Date(log.timestamp).toLocaleString('he-IL')}</td>
                      <td>{String(log.roomNumber).padStart(2, '0')}</td>
                      <td>{log.identifiedSpeaker || '—'}</td>
                      <td>{fmtConf(log.confidence)}</td>
                      <td>
                        <span className={`ap-pill ${log.accessGranted ? 'on' : 'off'}`}>
                          {log.accessGranted ? 'אושר' : 'נדחה'}
                        </span>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </section>
          </div>
        )}
      </div>
    </>
  )
}

const CSS = `
.ap-root {
  position: fixed; inset: 0;
  background: #0e0c08;
  color: #e8d9b8;
  font-family: 'Segoe UI', system-ui, sans-serif;
  overflow-y: auto;
}
.ap-header {
  display: flex; align-items: center; gap: 24px;
  padding: 18px 28px; border-bottom: 1px solid rgba(200,148,52,.2);
  position: sticky; top: 0; background: rgba(14,12,8,.96); z-index: 5;
}
.ap-brand { color: #d9a544; font-weight: 700; letter-spacing: 2px; font-size: 14px; }
.ap-tabs  { display: flex; gap: 8px; flex: 1; }
.ap-tab {
  background: none; border: 1px solid rgba(200,148,52,.25); border-radius: 8px;
  color: #a8987a; padding: 8px 16px; font-size: 13px; cursor: pointer;
}
.ap-tab.active { background: rgba(217,165,68,.16); color: #f0e0b8; border-color: rgba(217,165,68,.5); }
.ap-logout {
  background: none; border: 1px solid rgba(224,86,107,.4); border-radius: 8px;
  color: #e0566b; padding: 8px 16px; font-size: 12px; cursor: pointer;
}

.ap-err { margin: 14px 28px 0; padding: 10px 14px; background: rgba(224,86,107,.12); border: 1px solid rgba(224,86,107,.35); border-radius: 8px; color: #e0566b; font-size: 13px; }
.ap-loading { padding: 60px; text-align: center; color: #8a7a60; }

.ap-content { padding: 28px; display: flex; flex-direction: column; gap: 26px; max-width: 1100px; margin: 0 auto; }
.ap-card { background: rgba(255,255,255,.02); border: 1px solid rgba(200,148,52,.15); border-radius: 12px; padding: 22px; }
.ap-card h2 { font-size: 15px; color: #d9a544; margin-bottom: 16px; font-weight: 600; }

.ap-reg-form { display: flex; gap: 10px; flex-wrap: wrap; align-items: center; }
.ap-input {
  flex: 1; min-width: 160px; padding: 10px 12px;
  background: rgba(0,0,0,.3); border: 1px solid rgba(200,148,52,.25);
  border-radius: 8px; color: #f0e6d2; font-size: 13px;
}
.ap-file { color: #a8987a; }
.ap-btn {
  padding: 10px 20px; border: none; border-radius: 8px;
  background: linear-gradient(180deg, #d9a544, #b8842c); color: #1a1208;
  font-weight: 700; font-size: 13px; cursor: pointer;
}

.ap-table { width: 100%; border-collapse: collapse; font-size: 13px; }
.ap-table th { text-align: right; padding: 10px 8px; color: #8a7a60; font-weight: 500; border-bottom: 1px solid rgba(200,148,52,.2); }
.ap-row td { padding: 10px 8px; border-bottom: 1px solid rgba(255,255,255,.04); }
.ap-room-cell { text-align: center; }
.ap-pill { border: none; border-radius: 999px; padding: 5px 12px; font-size: 11px; cursor: pointer; font-weight: 600; }
.ap-pill.on  { background: rgba(79,216,122,.18); color: #4fd87a; }
.ap-pill.off { background: rgba(224,86,107,.18); color: #e0566b; }
`
