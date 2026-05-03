import { useState, useEffect } from 'react'
import axios from 'axios'

function AdminPanel() {
  const [users, setUsers] = useState([])
  const [logs, setLogs] = useState([]) // משתנה חדש ללוגים
  const [newUser, setNewUser] = useState("")
  const [files, setFiles] = useState(null)
  const [loading, setLoading] = useState(false)
  const [status, setStatus] = useState("")

  useEffect(() => {
    fetchData()
  }, [])

  const fetchData = async () => {
    try {
      // טעינת משתמשים
      const usersRes = await axios.get("http://localhost:8080/api/users")
      setUsers(usersRes.data)

      // טעינת לוגים (החלק החדש)
      const logsRes = await axios.get("http://localhost:8080/api/logs")
      setLogs(logsRes.data)
    } catch (error) {
      console.error(error)
      alert("שגיאה בטעינת נתונים")
    }
  }

  const toggleAuth = async (id) => {
    await axios.put(`http://localhost:8080/api/users/${id}/toggle`)
    fetchData()
  }

  const addUserWithSamples = async () => {
    if (!newUser || !files || files.length < 3) {
      alert("חובה להזין שם ולבחור לפחות 3 הקלטות!")
      return
    }
    setLoading(true)
    setStatus("מעבד...")

    const formData = new FormData()
    formData.append("username", newUser)
    formData.append("fullName", newUser)
    for (let i = 0; i < files.length; i++) {
      formData.append("samples", files[i])
    }

    try {
      await axios.post("http://localhost:8080/api/users/register", formData, {
        headers: { "Content-Type": "multipart/form-data" }
      })
      setStatus("המשתמש נוסף בהצלחה! ✅")
      setNewUser("")
      setFiles(null)
      fetchData()
    } catch (error) {
      setStatus("שגיאה בהוספה ❌")
    } finally {
      setLoading(false)
    }
  }

  return (
    <div style={{ padding: "20px", background: "#f4f4f9", direction: "rtl", minHeight: "100vh" }}>

      {/* --- חלק 1: ניהול משתמשים --- */}
      <div style={{ background: "white", padding: "20px", borderRadius: "10px", boxShadow: "0 2px 5px rgba(0,0,0,0.1)", marginBottom: "30px" }}>
        <h2 style={{borderBottom: "2px solid #007bff", paddingBottom: "10px", color: "#333"}}>ניהול משתמשים ואימון 👮‍♂️</h2>

        {/* טופס הוספה */}
        <div style={{ display: "flex", gap: "10px", marginBottom: "20px", alignItems: "center", background: "#f8f9fa", padding: "15px", borderRadius: "5px" }}>
            <input
              style={{padding: "8px", border: "1px solid #ccc", borderRadius: "4px"}}
              placeholder="שם עובד חדש (אנגלית)..."
              value={newUser}
              onChange={(e) => setNewUser(e.target.value)}
            />
            <input type="file" multiple accept="audio/*" onChange={(e) => setFiles(e.target.files)} />
            <button onClick={addUserWithSamples} disabled={loading} style={{cursor: "pointer", background: "#28a745", color: "white", border: "none", padding: "8px 15px", borderRadius: "4px"}}>
              {loading ? "מאמן מודל..." : "הוסף ואמן"}
            </button>
            <span style={{fontWeight: "bold"}}>{status}</span>
        </div>

        {/* טבלת משתמשים */}
        <table style={{ width: "100%", borderCollapse: "collapse" }}>
          <thead>
            <tr style={{background: "#333", color: "white"}}>
              <th style={{padding: "10px"}}>ID</th>
              <th>שם</th>
              <th>סטטוס</th>
              <th>פעולה</th>
            </tr>
          </thead>
          <tbody>
            {users.map(user => (
              <tr key={user.id} style={{ borderBottom: "1px solid #eee", textAlign: "center" }}>
                <td style={{padding: "10px"}}>{user.id}</td>
                <td><strong>{user.username}</strong></td>
                <td>{user.authorized ? <span style={{color:"green"}}>מורשה ✅</span> : <span style={{color:"red"}}>חסום ⛔</span>}</td>
                <td>
                  <button onClick={() => toggleAuth(user.id)} style={{cursor:"pointer", padding:"5px 10px"}}>
                    {user.authorized ? "חסום" : "אשר"}
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {/* --- חלק 2: יומן כניסות (החדש!) --- */}
      <div style={{ background: "white", padding: "20px", borderRadius: "10px", boxShadow: "0 2px 5px rgba(0,0,0,0.1)" }}>
        <h2 style={{borderBottom: "2px solid #ffc107", paddingBottom: "10px", color: "#333"}}>יומן כניסות (Logs) 📜</h2>

        <div style={{maxHeight: "400px", overflowY: "auto"}}>
            <table style={{ width: "100%", borderCollapse: "collapse", fontSize: "0.9rem" }}>
            <thead style={{position: "sticky", top: 0}}>
                <tr style={{background: "#6c757d", color: "white"}}>
                <th style={{padding: "10px"}}>מזהה</th>
                <th>תאריך ושעה</th>
                <th>דובר שזוהה</th>
                <th>שם הקובץ</th>
                <th>תוצאה</th>
                </tr>
            </thead>
            <tbody>
                {logs.map(log => (
                <tr key={log.id} style={{ borderBottom: "1px solid #eee", textAlign: "center", background: log.accessGranted ? "#e6fffa" : "#fff5f5" }}>
                    <td style={{padding: "8px"}}>{log.id}</td>
<td style={{direction: "ltr"}}>
  {new Date(log.timestamp).toLocaleString('he-IL', {
      day: '2-digit',
      month: '2-digit',
      year: 'numeric',
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit'
  })}
</td>                    <td><strong>{log.identifiedSpeaker}</strong></td>
                    <td style={{color: "#666", fontSize: "0.8rem"}}>{log.filename}</td>
                    <td>
                    {log.accessGranted ?
                        <span style={{color: "green", fontWeight: "bold"}}>אושר ✔</span> :
                        <span style={{color: "red", fontWeight: "bold"}}>נדחה ❌</span>
                    }
                    </td>
                </tr>
                ))}
            </tbody>
            </table>
        </div>
      </div>

    </div>
  )
}

export default AdminPanel