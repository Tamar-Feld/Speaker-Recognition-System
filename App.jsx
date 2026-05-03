import { useState } from 'react'
import axios from 'axios'
import AdminPanel from './AdminPanel'
import './App.css'

function App() {
  const [file, setFile] = useState(null)
  const[message, setMessage] = useState("")
  const [isAdminMode, setIsAdminMode] = useState(false)
  const [isProcessing, setIsProcessing] = useState(false)

  // הוספנו משתנה חדש: איזה חדר המשתמש מנסה לפתוח? (ברירת מחדל: חדר 1)
  const [selectedRoom, setSelectedRoom] = useState(1)

  const handleFileChange = (e) => {
    setFile(e.target.files[0])
    setMessage("")
  }

  const handleUpload = async () => {
    if (!file) {
      alert("אנא בחר קובץ קודם!")
      return
    }

    const formData = new FormData()
    formData.append("file", file)
    // התיקון הקריטי: אנחנו מצרפים את מספר החדר לבקשה!
    formData.append("roomId", selectedRoom)

    try {
      setIsProcessing(true)
      setMessage("מעבד נתונים... אנא המתן (מבצע ניקוי רעשים וזיהוי ביומטרי)")

      const response = await axios.post("http://localhost:8080/api/audio/upload", formData, {
        headers: { "Content-Type": "multipart/form-data" },
        responseType: 'text'
      })

      setMessage(response.data)
    } catch (error) {
      console.error("Error:", error)
      setMessage("שגיאה בתקשורת עם השרת (בדוק שהשרת פועל).")
    } finally {
      setIsProcessing(false)
    }
  }

  return (
    <div style={{ minHeight: "100vh", padding: "20px", direction: "rtl", textAlign: "center", fontFamily: "Arial, sans-serif" }}>

      <button
        onClick={() => setIsAdminMode(!isAdminMode)}
        style={{
          position: "absolute", top: 20, left: 20,
          background: isAdminMode ? "#6c757d" : "#007bff",
          color: "white", border: "none", padding: "10px", borderRadius: "5px", cursor: "pointer"
        }}
      >
        {isAdminMode ? "חזור למסך כניסה" : "כניסת מנהל מערכת"}
      </button>

      <h1 style={{fontSize: "3rem", marginBottom: "10px"}}>🛡️ מערכת בקרת כניסה ביומטרית</h1>
      <p style={{marginBottom: "40px", color: "#666"}}>זיהוי דובר מאובטח באמצעות AI</p>

      {isAdminMode ? (
        <AdminPanel />
      ) : (
        <div style={{ maxWidth: "500px", margin: "0 auto", padding: "40px", border: "1px solid #ccc", borderRadius: "10px", boxShadow: "0 4px 8px rgba(0,0,0,0.1)" }}>
          <h3>בדיקת אישור כניסה</h3>

          {/* התפריט החדש לבחירת חדר */}
          <div style={{ margin: "20px 0", padding: "15px", background: "#f8f9fa", borderRadius: "8px" }}>
            <label style={{ fontWeight: "bold", marginLeft: "10px" }}>לאיזה חדר ברצונך להיכנס?</label>
            <select
              value={selectedRoom}
              onChange={(e) => setSelectedRoom(e.target.value)}
              style={{ padding: "8px", fontSize: "1.1rem", borderRadius: "5px" }}
            >
              <option value="1">חדר 1 - קבלה</option>
              <option value="2">חדר 2 - משרדים</option>
              <option value="3">חדר 3 - ארכיון סודי</option>
              <option value="4">חדר 4 - חדר שרתים</option>
              <option value="5">חדר 5 - מעבדה</option>
              <option value="6">חדר 6 - חדר כספות</option>
            </select>
          </div>

          <input
            type="file"
            onChange={handleFileChange}
            accept="audio/*"
            style={{ display: "block", margin: "20px auto" }}
          />

          <button
            onClick={handleUpload}
            disabled={isProcessing}
            style={{
              padding: "15px 30px", fontSize: "1.2rem", cursor: "pointer",
              background: isProcessing ? "#ccc" : "#28a745",
              color: "white", border: "none", borderRadius: "5px", width: "100%"
            }}
          >
            {isProcessing ? "מעבד..." : "בצע זיהוי ופתח דלת"}
          </button>

          {message && (
            <div style={{ marginTop: "20px", padding: "15px", background: "#f8f9fa", borderRadius: "5px", border: "1px solid #ddd", whiteSpace: "pre-wrap" }}>
              <strong>תוצאת המערכת:</strong><br/>
              {message}
            </div>
          )}
        </div>
      )}
    </div>
  )
}

export default App