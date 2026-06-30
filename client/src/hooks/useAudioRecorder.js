import { useState, useRef, useCallback } from 'react'

// idle | recording | stopped
export default function useAudioRecorder () {
  const [state,       setState]       = useState('idle')
  const [isRecording, setIsRecording] = useState(false)
  const [audioBlob,   setAudioBlob]   = useState(null)
  const [error,       setError]       = useState(null)

  const mediaRecorderRef = useRef(null)
  const chunksRef        = useRef([])

  const startRecording = useCallback(async () => {
    try {
      setAudioBlob(null)
      setError(null)
      chunksRef.current = []

      const stream = await navigator.mediaDevices.getUserMedia({ audio: true })
      const mr     = new MediaRecorder(stream, { mimeType: 'audio/webm' })
      mediaRecorderRef.current = mr

      mr.ondataavailable = (e) => { if (e.data.size > 0) chunksRef.current.push(e.data) }
      mr.onstop = () => {
        const blob = new Blob(chunksRef.current, { type: 'audio/webm' })
        setAudioBlob(blob)
        setState('stopped')
        setIsRecording(false)
        stream.getTracks().forEach(t => t.stop())
      }

      mr.start()
      setState('recording')
      setIsRecording(true)
    } catch (err) {
      setError(err.message || 'מצלמה/מיקרופון לא זמינים')
      setState('idle')
      setIsRecording(false)
    }
  }, [])

  const stopRecording = useCallback(() => {
    if (mediaRecorderRef.current && mediaRecorderRef.current.state !== 'inactive') {
      mediaRecorderRef.current.stop()
    }
  }, [])

  return { state, isRecording, audioBlob, startRecording, stopRecording, error, setAudioBlob }
}
