// CorridorPage.jsx — SpeakKey Biometric Lobby
// Canvas-based 2D perspective lobby (single <canvas>, DPR-corrected) + ScanModal
// integrated with the Spring Boot API. JSON response shape from the server:
//   { accessGranted, identifiedSpeaker, confidence (0-1), message }
//
// Why canvas and not 6 real DOM elements rotated with CSS `preserve-3d`?
// An earlier version did exactly that (.ck-door { transform: rotateY(...) translateZ(...) }
// inside a `.ck-world { transform-style: preserve-3d }` wrapper) but it collapsed into a
// single flat door because `overflow: hidden` on an ancestor silently disables
// `preserve-3d` in every browser. Rather than chase that down through the whole
// ancestor chain, the lobby — ceiling, floor, all 6 doors — is drawn each frame
// on one canvas using plain 2D perspective math. No CSS 3D, no clipping bugs.

import { useState, useRef, useEffect, useCallback } from 'react'
import { useNavigate } from 'react-router-dom'
import useAudioRecorder from '../hooks/useAudioRecorder'
import { uploadAudio } from '../services/api'

/* ════════════════════════════════════════════════════════════════════════
   LAYOUT CONSTANTS — fractions of the canvas's CSS (logical) width/height
═════════════════════════════════════════════════════════════════════════ */
const VP_Y_F = 0.350   // vanishing point Y (horizon, concealed behind the back wall)
const WT_Y_F = 0.053   // back-wall top edge (wall meets ceiling)
const WB_Y_F = 0.511   // back-wall base edge — doors stand on the floor HERE
const DT_Y_F = 0.261   // door top
const DW_F   = 0.103   // door width, as a fraction of W, at the wall-base scale

// 6 door centres, evenly spaced across the back wall (left → right)
const DX_F = [0.0794, 0.2471, 0.4147, 0.5824, 0.7500, 0.9176]

// Per-door angle — purely cosmetic now (a holdover from the original CSS
// rotateY arc): used only to add a faint perspective skew/shade per door so
// the row doesn't look perfectly flat-on, without any real 3D transform.
const DOOR_ANGLES = [-52, -30, -10, 10, 30, 52]

const ROOMS = ['01', '02', '03', '04', '05', '06']

const NW   = 11   // light columns (ceiling + floor)
const NR_C = 6    // ceiling light rows
const NR_F = 6    // floor reflection rows

// Deterministic flicker seeds — computed once, not re-rolled every frame
const FLICK = Array.from({ length: (NR_C + NR_F) * NW }, (_, i) => ({
  ph: (i * 2.618) % (Math.PI * 2),
  f1: 0.68  + ((i * 0.042) % 0.54),
  a1: 0.030 + ((i * 0.016) % 0.042),
}))

const lerp = (a, b, t) => a + (b - a) * t

/* ════════════════════════════════════════════════════════════════════════
   getDoorRects — single source of truth for door geometry.
   Used by BOTH drawLobby() and the mouse hit-test, so what you see is
   always exactly what you can click.
═════════════════════════════════════════════════════════════════════════ */
function getDoorRects (W, H) {
  const top    = H * DT_Y_F
  const bottom = H * WB_Y_F
  const height = bottom - top

  return DX_F.map((xf, i) => {
    const cx    = W * xf
    const width = W * DW_F
    return {
      i,
      room: i + 1,
      label: ROOMS[i],
      angle: DOOR_ANGLES[i],
      x: cx - width / 2,
      y: top,
      w: width,
      h: height,
      cx,
      cy: top + height / 2,
    }
  })
}

/* ════════════════════════════════════════════════════════════════════════
   drawLobby — paints the entire scene for one frame
═════════════════════════════════════════════════════════════════════════ */
function drawLobby (ctx, W, H, hoveredIdx, ts) {
  const t = ts * 0.001
  ctx.clearRect(0, 0, W, H)

  const vpx = W * 0.5
  const wallTopY  = H * WT_Y_F
  const wallBotY  = H * WB_Y_F
  const farHalfW  = W * 0.13   // half-width of the back wall at its far edge

  /* ── Background ───────────────────────────────────────────────────── */
  const bg = ctx.createLinearGradient(0, 0, 0, H)
  bg.addColorStop(0,    '#0c0d13')
  bg.addColorStop(0.45, '#14131a')
  bg.addColorStop(1,    '#1c1610')
  ctx.fillStyle = bg
  ctx.fillRect(0, 0, W, H)

  /* ── CEILING (trapezoid: wide at y=0, narrows to the back wall) ─────── */
  ctx.save()
  ctx.beginPath()
  ctx.moveTo(0, 0)
  ctx.lineTo(W, 0)
  ctx.lineTo(vpx + farHalfW, wallTopY)
  ctx.lineTo(vpx - farHalfW, wallTopY)
  ctx.closePath()
  const ceilGrad = ctx.createLinearGradient(0, 0, 0, wallTopY)
  ceilGrad.addColorStop(0, '#1f1c16')
  ceilGrad.addColorStop(1, '#0c0a07')
  ctx.fillStyle = ceilGrad
  ctx.fill()
  ctx.clip()

  // converging structural beams
  ctx.strokeStyle = 'rgba(4,2,0,.78)'
  for (let i = 0; i <= NW; i++) {
    const f = i / NW
    ctx.lineWidth = lerp(2.4, 0.4, f)
    ctx.beginPath()
    ctx.moveTo(f * W, 0)
    ctx.lineTo(vpx, wallTopY)
    ctx.stroke()
  }

  // amber ceiling lights, NR_C rows × NW cols, perspective-compressed toward the far edge
  for (let r = 0; r < NR_C; r++) {
    const rf = r / (NR_C - 1)                 // 0 = near (top), 1 = far (wall)
    const y  = lerp(H * 0.02, wallTopY * 0.94, rf)
    const ps = lerp(1, 0.32, rf)               // perspective scale
    for (let c = 0; c < NW; c++) {
      const cf = c / (NW - 1)
      const x  = lerp(W * 0.06, W * 0.94, cf) * (1 - rf) + vpx * rf
      const fk = FLICK[r * NW + c]
      const br = fk.f1 + fk.a1 * Math.sin(t * 1.7 + fk.ph)
      const glow = ctx.createRadialGradient(x, y, 0, x, y, 22 * ps)
      glow.addColorStop(0,   `rgba(255,196,68,${0.30 * br})`)
      glow.addColorStop(0.4, `rgba(220,148,34,${0.12 * br})`)
      glow.addColorStop(1,   'rgba(0,0,0,0)')
      ctx.fillStyle = glow
      ctx.beginPath()
      ctx.ellipse(x, y, 22 * ps, 13 * ps, 0, 0, Math.PI * 2)
      ctx.fill()
      ctx.fillStyle = `rgba(255,214,92,${Math.min(1, br)})`
      ctx.beginPath()
      ctx.ellipse(x, y, 3 * ps, 2 * ps, 0, 0, Math.PI * 2)
      ctx.fill()
    }
  }
  ctx.restore()

  /* ── FLOOR (trapezoid: narrow at the wall base, wide at y=H) ─────────── */
  ctx.save()
  ctx.beginPath()
  ctx.moveTo(0, H)
  ctx.lineTo(W, H)
  ctx.lineTo(vpx + farHalfW, wallBotY)
  ctx.lineTo(vpx - farHalfW, wallBotY)
  ctx.closePath()
  const floorGrad = ctx.createLinearGradient(0, wallBotY, 0, H)
  floorGrad.addColorStop(0,    '#9a9080')
  floorGrad.addColorStop(0.3,  '#c4b8a6')
  floorGrad.addColorStop(0.65, '#d8d0c2')
  floorGrad.addColorStop(1,    '#c8c0b4')
  ctx.fillStyle = floorGrad
  ctx.fill()
  ctx.clip()

  // perspective tile grid
  ctx.strokeStyle = 'rgba(130,114,92,.28)'
  ctx.lineWidth = 1
  for (let i = 0; i <= NW; i++) {
    const f = i / NW
    ctx.beginPath()
    ctx.moveTo(f * W, H)
    ctx.lineTo(vpx, wallBotY)
    ctx.stroke()
  }
  for (let r = 1; r < 6; r++) {
    const f = 1 - 1 / (1 + r * 0.7)   // compress rows toward the far edge
    const y = lerp(H, wallBotY, f)
    ctx.beginPath()
    ctx.moveTo(lerp(0, vpx - farHalfW, f), y)
    ctx.lineTo(lerp(W, vpx + farHalfW, f), y)
    ctx.stroke()
  }

  // reflected amber light pools, NR_F rows
  for (let r = 0; r < NR_F; r++) {
    const rf = r / (NR_F - 1)
    const y  = lerp(H * 0.97, wallBotY * 1.05, rf)
    const ps = lerp(1, 0.3, rf)
    for (let c = 0; c < NW; c++) {
      const cf = c / (NW - 1)
      const x  = lerp(W * 0.06, W * 0.94, cf) * (1 - rf) + vpx * rf
      const fk = FLICK[(NR_C + r) * NW + c]
      const br = (fk.f1 + fk.a1 * Math.sin(t * 1.7 + fk.ph)) * 0.4
      const refl = ctx.createRadialGradient(x, y, 0, x, y, 30 * ps)
      refl.addColorStop(0, `rgba(255,214,140,${0.10 * br})`)
      refl.addColorStop(1, 'rgba(0,0,0,0)')
      ctx.fillStyle = refl
      ctx.beginPath()
      ctx.ellipse(x, y, 30 * ps, 44 * ps, 0, 0, Math.PI * 2)
      ctx.fill()
    }
  }

  // gloss sheen
  const gloss = ctx.createLinearGradient(0, wallBotY, 0, H)
  gloss.addColorStop(0, 'rgba(255,248,235,0)')
  gloss.addColorStop(1, 'rgba(255,248,235,.07)')
  ctx.fillStyle = gloss
  ctx.fill()
  ctx.restore()

  /* ── SIDE WALL VIGNETTES ─────────────────────────────────────────────── */
  ;[0, 1].forEach((side) => {
    const grad = ctx.createLinearGradient(side ? W : 0, 0, side ? W * 0.78 : W * 0.22, 0)
    grad.addColorStop(0, 'rgba(8,7,5,.7)')
    grad.addColorStop(1, 'rgba(0,0,0,0)')
    ctx.fillStyle = grad
    ctx.fillRect(side ? W * 0.78 : 0, 0, W * 0.22, H)
  })

  /* ── BACK WALL header band (above the doors) ─────────────────────────── */
  ctx.fillStyle = '#15110b'
  ctx.fillRect(vpx - farHalfW, wallTopY, farHalfW * 2, (H * DT_Y_F) - wallTopY)

  /* ── 6 DOORS ──────────────────────────────────────────────────────────── */
  const doors = getDoorRects(W, H)
  doors.forEach((d) => {
    const hovered = d.i === hoveredIdx
    const lift    = hovered ? 6 : 0               // hover = door "steps forward"
    const skew    = (d.angle / 52) * 5             // faint perspective skew, ±5px
    const x = d.x, y = d.y - lift / 2, w = d.w, h = d.h + lift

    ctx.save()

    // wood louver side-panels flanking the actual door (matches the louvre
    // wall cladding from the reference photo)
    const slatW = w * 0.18
    ;[x - slatW, x + w].forEach((sx) => {
      const slat = ctx.createLinearGradient(0, y, 0, y + h)
      slat.addColorStop(0,   '#7c5234')
      slat.addColorStop(0.5, '#5e3a1e')
      slat.addColorStop(1,   '#7c5234')
      ctx.fillStyle = slat
      ctx.fillRect(sx, y, slatW, h)
      ctx.strokeStyle = 'rgba(0,0,0,.5)'
      ctx.lineWidth = 1
      for (let ly = y + 6; ly < y + h; ly += 9) {
        ctx.beginPath(); ctx.moveTo(sx, ly); ctx.lineTo(sx + slatW, ly); ctx.stroke()
      }
    })

    // door frame
    ctx.beginPath()
    ctx.moveTo(x + skew, y)
    ctx.lineTo(x + w + skew, y)
    ctx.lineTo(x + w - skew, y + h)
    ctx.lineTo(x - skew, y + h)
    ctx.closePath()
    const frameGrad = ctx.createLinearGradient(0, y, 0, y + h)
    frameGrad.addColorStop(0,   '#623e22')
    frameGrad.addColorStop(0.5, '#3e2614')
    frameGrad.addColorStop(1,   '#623e22')
    ctx.fillStyle = frameGrad
    ctx.fill()

    // horizontal louver lines on the door leaf itself
    ctx.save(); ctx.clip()
    ctx.strokeStyle = 'rgba(0,0,0,.42)'
    for (let ly = y + 8; ly < y + h; ly += 11) {
      ctx.beginPath(); ctx.moveTo(x - skew, ly); ctx.lineTo(x + w + skew, ly); ctx.stroke()
    }
    // interior warm glow glimpsed through the door, brighter on hover
    const glow = ctx.createRadialGradient(x + w * 0.32, y + h * 0.5, 0, x + w * 0.32, y + h * 0.5, w * 0.9)
    glow.addColorStop(0, `rgba(255,210,120,${hovered ? 0.16 : 0.06})`)
    glow.addColorStop(1, 'rgba(0,0,0,0)')
    ctx.fillStyle = glow
    ctx.fillRect(x, y, w, h)
    ctx.restore()

    // brass trim border
    ctx.strokeStyle = hovered ? 'rgba(230,178,72,.9)' : 'rgba(178,128,45,.6)'
    ctx.lineWidth = hovered ? 3 : 2
    ctx.beginPath()
    ctx.moveTo(x + skew, y)
    ctx.lineTo(x + w + skew, y)
    ctx.lineTo(x + w - skew, y + h)
    ctx.lineTo(x - skew, y + h)
    ctx.closePath()
    ctx.stroke()

    // keypad (small panel, right edge of the door) + LED
    const kpX = x + w - 22, kpY = y + h * 0.42
    ctx.fillStyle = '#0e0c09'
    ctx.fillRect(kpX, kpY, 16, 26)
    ctx.strokeStyle = 'rgba(178,128,45,.5)'
    ctx.strokeRect(kpX, kpY, 16, 26)
    ctx.fillStyle = hovered ? '#ffce5c' : '#5a4322'
    ctx.beginPath()
    ctx.arc(kpX + 8, kpY + 7, hovered ? 3.4 : 2.4, 0, Math.PI * 2)
    ctx.fill()
    ctx.strokeStyle = 'rgba(255,210,130,.35)'
    for (let li = 0; li < 3; li++) {
      ctx.beginPath()
      ctx.moveTo(kpX + 4, kpY + 14 + li * 4)
      ctx.lineTo(kpX + 12, kpY + 14 + li * 4)
      ctx.stroke()
    }

    // room number label, centered above the keypad
    ctx.fillStyle = hovered ? '#ffe7ab' : '#cdb98c'
    ctx.font = '600 13px "Segoe UI", sans-serif'
    ctx.textAlign = 'center'
    ctx.fillText(d.label, d.cx, y + h + 18)

    ctx.restore()
  })
}

/* ════════════════════════════════════════════════════════════════════════
   CorridorPage — canvas + interaction + ScanModal host
═════════════════════════════════════════════════════════════════════════ */
export default function CorridorPage () {
  const canvasRef = useRef(null)
  const rafRef    = useRef(null)
  const sizeRef   = useRef({ W: 0, H: 0 })

  const [hoveredIdx, setHoveredIdx] = useState(-1)
  const [activeRoom, setActiveRoom] = useState(null)

  // keep the rAF loop's hovered value fresh without re-creating the whole
  // canvas-setup effect on every hover change
  const hoveredRef = useRef(hoveredIdx)
  useEffect(() => { hoveredRef.current = hoveredIdx }, [hoveredIdx])

  useEffect(() => {
    const canvas = canvasRef.current
    const ctx    = canvas.getContext('2d')
    const DPR    = window.devicePixelRatio || 1

    function resize () {
      const rect = canvas.getBoundingClientRect()
      const W = rect.width, H = rect.height
      canvas.width  = W * DPR
      canvas.height = H * DPR
      ctx.setTransform(DPR, 0, 0, DPR, 0, 0)
      sizeRef.current = { W, H }
    }
    resize()
    const ro = new ResizeObserver(resize)
    ro.observe(canvas)

    function animate (ts) {
      const { W, H } = sizeRef.current
      drawLobby(ctx, W, H, hoveredRef.current, ts)
      rafRef.current = requestAnimationFrame(animate)
    }
    rafRef.current = requestAnimationFrame(animate)

    return () => {
      cancelAnimationFrame(rafRef.current)
      ro.disconnect()
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const handleMouseMove = useCallback((e) => {
    const canvas = canvasRef.current
    const rect = canvas.getBoundingClientRect()
    const mx = e.clientX - rect.left
    const my = e.clientY - rect.top
    const doors = getDoorRects(rect.width, rect.height)
    const hit = doors.find(d => mx >= d.x && mx <= d.x + d.w && my >= d.y && my <= d.y + d.h)
    setHoveredIdx(hit ? hit.i : -1)
    canvas.style.cursor = hit ? 'pointer' : 'default'
  }, [])

  const handleClick = useCallback((e) => {
    const canvas = canvasRef.current
    const rect = canvas.getBoundingClientRect()
    const mx = e.clientX - rect.left
    const my = e.clientY - rect.top
    const doors = getDoorRects(rect.width, rect.height)
    const hit = doors.find(d => mx >= d.x && mx <= d.x + d.w && my >= d.y && my <= d.y + d.h)
    if (hit) setActiveRoom(hit.i + 1)
  }, [])

  return (
    <>
      <style>{CSS}</style>
      <div className="ck-root">
        <div className="ck-brand">SPEAKKEY</div>
        <a className="ck-admin-link" href="/login">ADMIN</a>
        <canvas
          ref={canvasRef}
          className="ck-canvas"
          onMouseMove={handleMouseMove}
          onMouseLeave={() => setHoveredIdx(-1)}
          onClick={handleClick}
        />
        <div className="ck-hint">לחצי על דלת כדי לזהות את קולך ולקבל גישה</div>

        {activeRoom && (
          <ScanModal roomId={activeRoom} onClose={() => setActiveRoom(null)} />
        )}
      </div>
    </>
  )
}

/* ════════════════════════════════════════════════════════════════════════
   ScanModal — idle → recording → uploading → granted/denied
═════════════════════════════════════════════════════════════════════════ */
function ScanModal ({ roomId, onClose }) {
  const [phase, setPhase]         = useState('idle') // idle | recording | uploading | granted | denied
  const [countdown, setCountdown] = useState(5)
  const [result, setResult]       = useState(null)
  const navigate = useNavigate()

  const { audioBlob, startRecording, stopRecording, error: micError } = useAudioRecorder()

  const stopTimer  = useRef(null)
  const cdInterval = useRef(null)

  const doRecord = async () => {
    setResult(null)
    setPhase('recording')
    setCountdown(5)
    await startRecording()

    cdInterval.current = setInterval(() => {
      setCountdown((cd) => {
        if (cd <= 1) {
          clearInterval(cdInterval.current)
          return 0
        }
        return cd - 1
      })
    }, 1000)

    stopTimer.current = setTimeout(() => {
      clearInterval(cdInterval.current)
      stopRecording()
    }, 5000)
  }

  const doStop = () => {
    clearTimeout(stopTimer.current)
    clearInterval(cdInterval.current)
    stopRecording()
  }

  useEffect(() => {
    if (!audioBlob) return
    setPhase('uploading')

    uploadAudio(audioBlob, roomId)
      .then(({ data }) => {
        const pct = Math.round((data.confidence || 0) * 100)
        setResult({ ...data, confidence: pct })
        setPhase(data.accessGranted ? 'granted' : 'denied')

        if (data.accessGranted) {
          setTimeout(() => {
            navigate(`/door/${roomId}`, {
              state: {
                granted:    true,
                speaker:    data.identifiedSpeaker,
                confidence: data.confidence,   // raw 0-1 — DoorPage does its own ×100
              },
            })
          }, 4000)
        }
      })
      .catch(() => {
        setResult({ accessGranted: false, message: '⛔ שגיאת תקשורת עם השרת' })
        setPhase('denied')
      })
  }, [audioBlob, navigate, roomId])

  useEffect(() => () => {
    clearTimeout(stopTimer.current)
    clearInterval(cdInterval.current)
  }, [])

  return (
    <div className="ck-backdrop" onClick={(e) => { if (e.target === e.currentTarget && phase === 'idle') onClose() }}>
      <div className="ck-modal" role="dialog" aria-modal="true">
        <div className="ck-modal-hd">
          <span>חדר {String(roomId).padStart(2, '0')}</span>
          {phase === 'idle' && <button className="ck-close" onClick={onClose}>✕</button>}
        </div>

        <div className="ck-modal-bd">
          {phase === 'idle' && (
            <div className="ck-idle">
              <div className="ck-mic-ic">🎙️</div>
              <p>לחצי על IDENTIFY והשמיעי כמה מילים בקול טבעי</p>
            </div>
          )}

          {phase === 'recording' && (
            <div className="ck-rec" role="status">
              <div className="ck-vis">
                {[1, 2, 3, 4, 5, 6].map((n) => (
                  <span key={n} className={`ck-bar ck-bar-${n}`} />
                ))}
              </div>
              <div className="ck-countdown">{countdown}</div>
              <div className="ck-cd-bar">
                <div className="ck-cd-fill" style={{ width: `${(countdown / 5) * 100}%` }} />
              </div>
            </div>
          )}

          {phase === 'uploading' && (
            <div className="ck-up" role="status">
              <div className="ck-spinner" />
              <p>מנתח טביעת קול… ECAPA-TDNN</p>
            </div>
          )}

          {(phase === 'granted' || phase === 'denied') && result && (
            <div className={`ck-res ck-res--${phase === 'granted' ? 'g' : 'd'}`} role="status">
              <div className="ck-resic">{phase === 'granted' ? '✓' : '✗'}</div>
              <div className="ck-resnm">
                {result.identifiedSpeaker || (phase === 'granted' ? 'מזוהה' : 'לא זוהה')}
              </div>
              {result.confidence > 0 && (
                <div className="ck-rescf">ביטחון: {Math.round(result.confidence)}%</div>
              )}
              {phase === 'granted' && (
                <div className="ck-resnav">פותח דלת… מועבר תוך 4 שניות</div>
              )}
              {phase === 'denied' && result.message && (
                <div className="ck-resmsg">{result.message.replace('⛔ ', '')}</div>
              )}
            </div>
          )}

          {micError && phase === 'idle' && (
            <div className="ck-err" role="alert">שגיאת מיקרופון: {micError}</div>
          )}
        </div>

        {phase === 'idle' && (
          <button className="ck-btn ck-btn--sc" onClick={doRecord}>▶  IDENTIFY</button>
        )}
        {phase === 'recording' && (
          <button className="ck-btn ck-btn--stop" onClick={doStop}>■  STOP &amp; SEND NOW</button>
        )}
        {phase === 'uploading' && (
          <button className="ck-btn ck-btn--pc" disabled>⟳  PROCESSING…</button>
        )}
        {phase === 'denied' && (
          <button className="ck-btn ck-btn--sc" onClick={onClose}>סגירה</button>
        )}
      </div>
    </div>
  )
}

/* ════════════════════════════════════════════════════════════════════════
   CSS — kept inline so this page is fully self-contained
═════════════════════════════════════════════════════════════════════════ */
const CSS = `
.ck-root {
  position: fixed; inset: 0;
  background: #0a0a0a;
  font-family: 'Segoe UI', system-ui, sans-serif;
}
.ck-canvas { position: absolute; inset: 0; width: 100%; height: 100%; display: block; }
.ck-brand {
  position: absolute; top: 22px; left: 28px; z-index: 5;
  color: #d9a544; font-weight: 700; letter-spacing: 3px; font-size: 15px;
  text-shadow: 0 2px 8px rgba(0,0,0,.6);
}
.ck-admin-link {
  position: absolute; top: 24px; right: 28px; z-index: 5;
  color: #8a7a60; font-size: 11px; letter-spacing: 2px; text-decoration: none;
}
.ck-admin-link:hover { color: #d9a544; }
.ck-hint {
  position: absolute; bottom: 28px; left: 0; right: 0; z-index: 5;
  text-align: center; color: rgba(230,220,200,.55); font-size: 13px;
  letter-spacing: .5px; pointer-events: none;
}

/* ── ScanModal ───────────────────────────────────────────────────────── */
.ck-backdrop {
  position: fixed; inset: 0; z-index: 50;
  background: rgba(4,3,2,.72);
  backdrop-filter: blur(3px);
  display: flex; align-items: center; justify-content: center;
}
.ck-modal {
  width: 340px; background: rgba(22,18,14,.96);
  border: 1px solid rgba(200,148,52,.32); border-radius: 16px;
  box-shadow: 0 24px 70px rgba(0,0,0,.65);
  padding: 0; overflow: hidden;
}
.ck-modal-hd {
  display: flex; align-items: center; justify-content: space-between;
  padding: 16px 18px; border-bottom: 1px solid rgba(200,148,52,.18);
  color: #e8d9b8; font-size: 13px; letter-spacing: 1px;
}
.ck-close { background: none; border: none; color: #8a7a60; cursor: pointer; font-size: 16px; }
.ck-modal-bd { padding: 28px 22px; min-height: 140px; display: flex; align-items: center; justify-content: center; }

.ck-idle { text-align: center; color: #c8b896; font-size: 13px; line-height: 1.6; }
.ck-mic-ic { font-size: 36px; margin-bottom: 10px; }

.ck-rec { width: 100%; text-align: center; }
.ck-vis { display: flex; gap: 6px; justify-content: center; align-items: flex-end; height: 36px; margin-bottom: 14px; }
.ck-bar { width: 5px; border-radius: 3px; background: #d9a544; display: inline-block; }
.ck-bar-1 { animation: barBounce1 .9s ease-in-out infinite; }
.ck-bar-2 { animation: barBounce2 .8s ease-in-out infinite; }
.ck-bar-3 { animation: barBounce3 1.0s ease-in-out infinite; }
.ck-bar-4 { animation: barBounce4 .85s ease-in-out infinite; }
.ck-bar-5 { animation: barBounce5 .95s ease-in-out infinite; }
.ck-bar-6 { animation: barBounce6 .75s ease-in-out infinite; }
.ck-countdown { font-size: 34px; font-weight: 700; color: #f0e0b8; margin-bottom: 10px; }
.ck-cd-bar { width: 100%; height: 4px; background: rgba(255,255,255,.1); border-radius: 2px; overflow: hidden; }
.ck-cd-fill { height: 100%; background: #d9a544; transition: width 1s linear; }

.ck-up { text-align: center; color: #c8b896; font-size: 13px; }
.ck-spinner {
  width: 34px; height: 34px; margin: 0 auto 14px;
  border: 3px solid rgba(217,165,68,.25); border-top-color: #d9a544;
  border-radius: 50%; animation: ckspin .9s linear infinite;
}
@keyframes ckspin { to { transform: rotate(360deg); } }

.ck-res { width: 100%; text-align: center; }
.ck-resic { font-size: 40px; margin-bottom: 6px; }
.ck-res--g .ck-resic { color: #4fd87a; }
.ck-res--d .ck-resic { color: #e0566b; }
.ck-resnm { font-size: 16px; color: #f0e6d2; font-weight: 600; margin-bottom: 4px; }
.ck-rescf { font-size: 12px; color: #a8987a; margin-bottom: 8px; }
.ck-resnav { font-size: 12px; color: #4fd87a; }
.ck-resmsg { font-size: 12px; color: #e0566b; }
.ck-err { color: #e0566b; font-size: 12px; text-align: center; margin-top: 10px; }

.ck-btn {
  display: block; width: 100%; padding: 14px; border: none;
  font-weight: 700; font-size: 13px; letter-spacing: 1px; cursor: pointer;
}
.ck-btn--sc   { background: linear-gradient(180deg, #d9a544, #b8842c); color: #1a1208; }
.ck-btn--stop { background: linear-gradient(180deg, #e0566b, #b8344a); color: #f4e4e4; }
.ck-btn--pc   { background: rgba(255,255,255,.08); color: #8a7a60; cursor: default; }
`
