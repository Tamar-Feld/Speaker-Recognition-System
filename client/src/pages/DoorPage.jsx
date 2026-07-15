// DoorPage.jsx — single-room access terminal.
// Reached only via navigate() from CorridorPage's ScanModal after a granted
// scan. Does NOT re-record or re-call the API — it purely animates the door
// opening using the result already carried in router state:
//   { granted: true, speaker, confidence (0-1, raw — converted here) }
// A direct visit with no state simply shows a neutral "no scan data" message.

import { useState, useEffect } from 'react'
import { useParams, useLocation, useNavigate } from 'react-router-dom'

export default function DoorPage () {
  const { id: roomId } = useParams()
  const { state }      = useLocation()
  const navigate        = useNavigate()

  const granted    = state?.granted ?? false
  const speaker     = state?.speaker ?? null
  const confidence  = state?.confidence ?? 0   // raw 0-1, as sent by the Python/Java pipeline

  const [isProcessing, setIsProcessing] = useState(Boolean(state))
  const [doorOpen, setDoorOpen]         = useState(false)

  const accessResult = state
    ? { accessGranted: granted, identifiedSpeaker: speaker }
    : null

  useEffect(() => {
    if (!state) { setIsProcessing(false); return }

    const t1 = setTimeout(() => setIsProcessing(false), 900)
    let t2
    if (granted) {
      t2 = setTimeout(() => setDoorOpen(true), 1100)
    }
    return () => { clearTimeout(t1); clearTimeout(t2) }
  }, [state, granted])

  const statusText = !state                     ? 'אין נתוני סריקה'
                    : accessResult.accessGranted ? 'גישה מאושרת ✓'
                    : accessResult               ? 'גישה נדחתה ✗'
                    : isProcessing                ? 'מנתח טביעת קול…' : 'מוכן לסריקה'

  const statusSub  = !state                      ? 'חזרי ללובי וסרקי דלת מחדש'
                    : accessResult.accessGranted  ? accessResult.identifiedSpeaker
                    : accessResult                ? 'זהות לא ידועה'
                    : isProcessing                 ? 'ECAPA-TDNN · EER 4.12%' : 'לחץ IDENTIFY להתחיל'

  return (
    <>
      <style>{CSS}</style>
      <div className="dp-root">
        <button className="dp-back" onClick={() => navigate('/')}>← LOBBY</button>
        <div className="dp-brand">SPEAKKEY</div>
        <div className="dp-zone">SECURE ZONE · BIOMETRIC ACCESS</div>

        <div className="dp-main">
          {/* Door 3D scene — perspective + preserve-3d, used ONLY here (a single
              door swinging open), not for the 6-door lobby which is canvas-drawn. */}
          <div className="dp-scene">
            <div className="dp-room-plate">ROOM {String(roomId).padStart(2, '0')}</div>
            <div className="dp-frame">
              <div className={`dp-interior${doorOpen ? ' show' : ''}`} />
              <div className="dp-leaf-wrap">
                <div className={`dp-leaf${doorOpen ? ' open' : ''}`} />
              </div>
            </div>
          </div>

          <div className="dp-status">
            <div className="dp-status-main">{isProcessing ? 'מנתח טביעת קול…' : statusText}</div>
            <div className="dp-status-sub">{isProcessing ? 'ECAPA-TDNN · EER 4.12%' : statusSub}</div>


            {!isProcessing && !granted && state && (
              <button className="dp-retry" onClick={() => navigate('/')}>חזרה ללובי לניסיון נוסף</button>
            )}
          </div>
        </div>
      </div>
    </>
  )
}

const CSS = `
.dp-root {
  position: fixed; inset: 0;
  background: radial-gradient(ellipse at 50% 20%, #1c170f 0%, #0a0806 75%);
  font-family: 'Segoe UI', system-ui, sans-serif;
  color: #e8d9b8;
  overflow-y: auto;
}
.dp-back {
  position: absolute; top: 22px; left: 28px; z-index: 5;
  background: none; border: 1px solid rgba(200,148,52,.3); border-radius: 6px;
  color: #c8b896; padding: 6px 12px; font-size: 12px; cursor: pointer;
}
.dp-brand { position: absolute; top: 24px; right: 28px; color: #d9a544; font-weight: 700; letter-spacing: 3px; font-size: 14px; }
.dp-zone  { position: absolute; top: 48px; right: 28px; color: #6e6048; font-size: 10px; letter-spacing: 1.5px; }

.dp-main {
  min-height: 100%;
  display: flex; flex-direction: column; align-items: center; justify-content: center;
  gap: 36px; padding: 110px 20px 60px;
}

/* ── 3D door scene — the ONLY place in the app using CSS perspective/preserve-3d ── */
.dp-scene { perspective: 1000px; }
.dp-room-plate {
  text-align: center; margin-bottom: 14px;
  color: #8a7a60; font-size: 11px; letter-spacing: 3px;
}
.dp-frame {
  position: relative; width: 220px; height: 360px;
  background: linear-gradient(180deg, #1e1610, #120e08);
  border: 3px solid rgba(178,128,45,.55);
  box-shadow: inset 0 0 28px rgba(0,0,0,.7), 0 20px 50px rgba(0,0,0,.55);
}
.dp-interior {
  position: absolute; inset: 6px;
  background: radial-gradient(ellipse 70% 70% at 50% 50%, rgba(255,210,120,0) 0%, transparent 70%);
  transition: background 1s ease;
}
.dp-interior.show {
  background: radial-gradient(ellipse 70% 70% at 50% 50%, rgba(255,210,120,.35) 0%, transparent 75%);
}
.dp-leaf-wrap {
  position: absolute; inset: 6px;
  transform-style: preserve-3d;
}
.dp-leaf {
  position: absolute; inset: 0;
  background: repeating-linear-gradient(
      180deg,
      rgba(0,0,0,0) 0px, rgba(0,0,0,0) 13px,
      rgba(0,0,0,.4) 13px, rgba(0,0,0,.4) 17px
    ),
    linear-gradient(180deg, #623e22 0%, #3e2614 50%, #623e22 100%);
  transform-origin: left center;
  transform: rotateY(0deg);
  transition: transform 1.1s cubic-bezier(.2,.8,.2,1);
  box-shadow: inset 0 0 18px rgba(0,0,0,.6);
}
.dp-leaf.open { transform: rotateY(-103deg); }

.dp-status { text-align: center; }
.dp-status-main { font-size: 18px; font-weight: 600; margin-bottom: 6px; }
.dp-status-sub  { font-size: 13px; color: #a8987a; }
.dp-conf  { margin-top: 10px; font-size: 12px; color: #c8b896; }
.dp-retry {
  margin-top: 18px; padding: 10px 22px; border: 1px solid rgba(200,148,52,.4);
  border-radius: 8px; background: none; color: #d9a544; font-size: 12px; cursor: pointer;
}
`
