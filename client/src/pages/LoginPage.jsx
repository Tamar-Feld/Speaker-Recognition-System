import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useAdminAuthContext } from '../context/AdminAuthContext.jsx'

const CSS = `
.lp-root {
  position: fixed; inset: 0;
  display: flex; align-items: center; justify-content: center;
  background: radial-gradient(ellipse at 50% 30%, #1a1610 0%, #0a0806 70%);
  font-family: 'Segoe UI', system-ui, sans-serif;
}
.lp-card {
  width: 340px; padding: 36px 32px;
  background: rgba(20,16,12,.92);
  border: 1px solid rgba(200,148,52,.35);
  border-radius: 14px;
  box-shadow: 0 20px 60px rgba(0,0,0,.6);
  text-align: center;
}
.lp-brand { font-size: 22px; letter-spacing: 4px; color: #d9a544; font-weight: 700; margin-bottom: 4px; }
.lp-sub   { font-size: 12px; color: #8a7a60; letter-spacing: 2px; margin-bottom: 28px; }
.lp-input {
  width: 100%; padding: 12px 14px; margin-bottom: 14px;
  background: rgba(0,0,0,.35); border: 1px solid rgba(200,148,52,.3);
  border-radius: 8px; color: #f0e6d2; font-size: 14px; outline: none;
  box-sizing: border-box;
}
.lp-input:focus { border-color: rgba(217,165,68,.8); }
.lp-btn {
  width: 100%; padding: 12px; border: none; border-radius: 8px;
  background: linear-gradient(180deg, #d9a544, #b8842c);
  color: #1a1208; font-weight: 700; font-size: 14px; letter-spacing: 1px;
  cursor: pointer; transition: filter .2s;
}
.lp-btn:hover:not(:disabled) { filter: brightness(1.1); }
.lp-btn:disabled { opacity: .6; cursor: default; }
.lp-err  { color: #e0566b; font-size: 12px; margin-bottom: 14px; }
.lp-back { display: block; margin-top: 18px; color: #8a7a60; font-size: 12px; text-decoration: none; }
`

export default function LoginPage () {
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError]       = useState('')
  const [loading, setLoading]   = useState(false)
  const { login }  = useAdminAuthContext()
  const navigate    = useNavigate()

  async function handleSubmit (e) {
    e.preventDefault()
    setError('')
    setLoading(true)
    const result = await login(username, password)
    setLoading(false)
    if (result.success) {
      navigate('/admin')
    } else {
      setError(result.message || 'שגיאת כניסה')
    }
  }

  return (
    <>
      <style>{CSS}</style>
      <div className="lp-root">
        <form className="lp-card" onSubmit={handleSubmit}>
          <div className="lp-brand">SPEAKKEY</div>
          <div className="lp-sub">ADMIN ACCESS</div>
          {error && <div className="lp-err">{error}</div>}
          <input
            className="lp-input"
            type="text"
            placeholder="שם משתמש"
            value={username}
            onChange={(e) => setUsername(e.target.value)}
            autoComplete="username"
            autoFocus
          />
          <input
            className="lp-input"
            type="password"
            placeholder="סיסמת מנהל"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            autoComplete="current-password"
          />
          <button className="lp-btn" type="submit" disabled={loading}>
            {loading ? 'מתחבר…' : 'התחבר'}
          </button>
          <a className="lp-back" href="/">← חזרה ללובי</a>
        </form>
      </div>
    </>
  )
}
