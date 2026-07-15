import { useState, useRef, useCallback } from 'react'
import { AUDIO_CONSTRAINTS } from './audioConstraints'

const TARGET_SR = 16_000

// Float32Array → WAV Blob (PCM 16-bit, mono, little-endian)
function encodeWav (samples, sr) {
  const buf  = new ArrayBuffer(44 + samples.length * 2)
  const view = new DataView(buf)
  const str  = (off, s) => { for (let i = 0; i < s.length; i++) view.setUint8(off + i, s.charCodeAt(i)) }
  str(0, 'RIFF');  view.setUint32(4,  36 + samples.length * 2, true)
  str(8, 'WAVE');  str(12, 'fmt ')
  view.setUint32(16, 16, true)              // PCM fmt chunk size
  view.setUint16(20,  1, true)              // format: PCM
  view.setUint16(22,  1, true)              // channels: 1
  view.setUint32(24, sr, true)              // sample rate
  view.setUint32(28, sr * 2, true)          // byte rate
  view.setUint16(32,  2, true)              // block align
  view.setUint16(34, 16, true)              // bits per sample
  str(36, 'data'); view.setUint32(40, samples.length * 2, true)
  for (let i = 0; i < samples.length; i++)
    view.setInt16(44 + i * 2,
      Math.max(-32768, Math.min(32767, Math.round(samples[i] * 32767))), true)
  return new Blob([buf], { type: 'audio/wav' })
}

// Resample Float32Array from srcRate to TARGET_SR via OfflineAudioContext
async function resampleTo16k (samples, srcRate) {
  if (srcRate === TARGET_SR) return samples
  const dur    = samples.length / srcRate
  const offCtx = new OfflineAudioContext(1, Math.ceil(dur * TARGET_SR), TARGET_SR)
  const buf    = offCtx.createBuffer(1, samples.length, srcRate)
  buf.copyToChannel(samples, 0)
  const src = offCtx.createBufferSource()
  src.buffer = buf
  src.connect(offCtx.destination)
  src.start(0)
  const rendered = await offCtx.startRendering()
  return rendered.getChannelData(0)
}

// idle | recording | stopped
export default function useAudioRecorder () {
  const [state,       setState]       = useState('idle')
  const [isRecording, setIsRecording] = useState(false)
  const [audioBlob,   setAudioBlob]   = useState(null)
  const [error,       setError]       = useState(null)

  const ctxRef     = useRef(null)
  const streamRef  = useRef(null)
  const samplesRef = useRef([])

  const startRecording = useCallback(async () => {
    try {
      setAudioBlob(null)
      setError(null)
      samplesRef.current = []

      const stream = await navigator.mediaDevices.getUserMedia(AUDIO_CONSTRAINTS)
      console.log('[audio-constraints]', stream.getAudioTracks()[0].getSettings())
      streamRef.current = stream

      const ctx = new AudioContext()
      ctxRef.current = ctx
      console.log('[audio-context] sampleRate:', ctx.sampleRate,
        ctx.sampleRate === TARGET_SR ? '— no resample needed' : `— will resample → ${TARGET_SR}`)

      await ctx.audioWorklet.addModule('/pcm-recorder.js')

      // numberOfOutputs:0 → sink node, processed without connecting to destination
      const worklet = new AudioWorkletNode(ctx, 'pcm-recorder', {
        numberOfOutputs: 0, channelCount: 1, channelCountMode: 'explicit',
      })
      worklet.port.onmessage = (e) => { samplesRef.current.push(e.data) }

      ctx.createMediaStreamSource(stream).connect(worklet)

      setState('recording')
      setIsRecording(true)
    } catch (err) {
      setError(err.message || 'מצלמה/מיקרופון לא זמינים')
      setState('idle')
      setIsRecording(false)
    }
  }, [])

  const stopRecording = useCallback(async () => {
    const ctx    = ctxRef.current
    const stream = streamRef.current
    if (!ctx || ctx.state === 'closed') return

    stream?.getTracks().forEach(t => t.stop())
    // wait 80ms so worklet port drains any in-flight frames before context closes
    await new Promise(r => setTimeout(r, 80))
    await ctx.close()

    const chunks = samplesRef.current
    if (!chunks.length) {
      setError('לא נקלטו נתוני שמע')
      setState('idle')
      setIsRecording(false)
      return
    }
    const total  = chunks.reduce((s, c) => s + c.length, 0)
    const merged = new Float32Array(total)
    let off = 0
    for (const c of chunks) { merged.set(c, off); off += c.length }

    const pcm  = await resampleTo16k(merged, ctx.sampleRate)
    const blob = encodeWav(pcm, TARGET_SR)

    setAudioBlob(blob)
    setState('stopped')
    setIsRecording(false)
  }, [])

  return { state, isRecording, audioBlob, startRecording, stopRecording, error, setAudioBlob }
}
