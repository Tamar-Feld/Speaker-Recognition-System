import { useState } from 'react'
import axios from 'axios'
import AdminPanel from './AdminPanel'
import './App.css'

function App() {
  const [file, setFile] = useState(null)
  const [message, setMessage] = useState("")
  const [isAdminMode, setIsAdminMode] = useState(false)
  const [isProcessing, setIsProcessing] = useState(false)

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

    try {
      setIsProcessing(true)
      setMessage("מעבד נתונים... אנא המתן (מבצע ניקוי רעשים וזיהוי ביומטרי)")

      const response = await axios.post("http://localhost:8080/api/audio/upload", formData, {
        headers: { "Content-Type": "multipart/form-data" },
        responseType: 'text'
      })

      setMessage(response.data) // הצגת התשובה מהשרת
    } catch (error) {
      console.error("Error:", error)
      setMessage("שגיאה בתקשורת עם השרת.")
    } finally {
      setIsProcessing(false)
    }
  }

  return (
    <div style={{ minHeight: "100vh", padding: "20px", direction: "rtl", textAlign: "center", fontFamily: "Arial, sans-serif" }}>

      {/* כפתור החלפת מצבים בפינה */}
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