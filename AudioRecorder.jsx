import React, { useState, useRef } from 'react';
import { Mic, Square } from 'lucide-react';

export default function AudioRecorder({ onFileReady }) {
  const [isRecording, setIsRecording] = useState(false);
  const [blobURL, setBlobURL] = useState('');

  // משתנים לשמירת ההקלטה
  const mediaRecorder = useRef(null);
  const audioChunks = useRef([]);

  const startRecording = async () => {
    try {
      // 1. בקשת גישה למיקרופון
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true });

      // 2. השימוש ב-API לאיסוף זרם השמע לתוך משתנה
      mediaRecorder.current = new MediaRecorder(stream);
      audioChunks.current = [];

      mediaRecorder.current.ondataavailable = (event) => {
        if (event.data.size > 0) {
          audioChunks.current.push(event.data);
        }
      };

      mediaRecorder.current.onstop = async () => {
        // כשמפסיקים - יוצרים "בלוב" מההקלטה הגולמית
        const webmBlob = new Blob(audioChunks.current, { type: 'audio/webm' });

        // 3. המרה קריטית: מ-WebM ל-WAV (כדי ש-C++ יצליח לקרוא)
        const wavFile = await convertToWav(webmBlob);

        // יצירת קישור להשמעה
        const url = URL.createObjectURL(wavFile);
        setBlobURL(url);

        // שליחה לאבא
        onFileReady(wavFile);
      };

      mediaRecorder.current.start();
      setIsRecording(true);
    } catch (err) {
      console.error(err);
      alert("לא ניתן לגשת למיקרופון.");
    }
  };

  const stopRecording = () => {
    if (mediaRecorder.current) {
      mediaRecorder.current.stop();
      setIsRecording(false);
      // כיבוי המיקרופון (כדי שהנורה האדומה בדפדפן תיכבה)
      mediaRecorder.current.stream.getTracks().forEach(track => track.stop());
    }
  };

  return (
    <div style={{ border: "2px dashed #ccc", padding: "20px", borderRadius: "10px", margin: "20px 0" }}>
      <h3>הזדהות קולית</h3>
      <div style={{ display: "flex", gap: "20px", justifyContent: "center" }}>
        {!isRecording ? (
          <button onClick={startRecording} style={btnStyle("#dc3545")}><Mic /> הקלט</button>
        ) : (
          <button onClick={stopRecording} style={btnStyle("#333")}><Square /> עצור</button>
        )}
      </div>
      {blobURL && (
        <div style={{ marginTop: "15px" }}>
          <audio src={blobURL} controls />
          <p style={{color: "green"}}>הקלטה מוכנה!</p>
        </div>
      )}
    </div>
  );
}

// === : המרה ל-WAV אמיתי ===
async function convertToWav(webmBlob) {
  // 1. הופכים את ה-Blob לאודיו בזיכרון
  const arrayBuffer = await webmBlob.arrayBuffer();
  const audioContext = new (window.AudioContext || window.webkitAudioContext)({ sampleRate: 16000 });

    // --- תוספת קריטית: לוודא שה-Context פעיל ---
    if (audioContext.state === 'suspended') {
      await audioContext.resume();
    }
  const audioBuffer = await audioContext.decodeAudioData(arrayBuffer);

  // 2. לוקחים רק את הערוץ הראשון (מונו)
  const channelData = audioBuffer.getChannelData(0);

  // 3. כתיבת כותרת WAV (RIFF Header)
  const buffer = new ArrayBuffer(44 + channelData.length * 2);
  const view = new DataView(buffer);

  // כתיבת הכותרת שתעבור את בדיקת ה-C++
  writeString(view, 0, 'RIFF');
  view.setUint32(4, 36 + channelData.length * 2, true);
  writeString(view, 8, 'WAVE');
  writeString(view, 12, 'fmt ');
  view.setUint32(16, 16, true);
  view.setUint16(20, 1, true); // PCM format
  view.setUint16(22, 1, true); // Mono
  view.setUint32(24, 16000, true); // Sample Rate
  view.setUint32(28, 16000 * 2, true); // Byte Rate
  view.setUint16(32, 2, true);
  view.setUint16(34, 16, true); // 16-bit
  writeString(view, 36, 'data');
  view.setUint32(40, channelData.length * 2, true);

  // 4. כתיבת הדאטה (המרת Float ל-PCM 16bit)
  let offset = 44;
  for (let i = 0; i < channelData.length; i++) {
    const s = Math.max(-1, Math.min(1, channelData[i]));
    // המרה ל-16 ביט (בין -32768 ל-32767)
    view.setInt16(offset, s < 0 ? s * 0x8000 : s * 0x7FFF, true);
    offset += 2;
  }

  return new File([view], "mic-recording.wav", { type: "audio/wav" });
}

function writeString(view, offset, string) {
  for (let i = 0; i < string.length; i++) {
    view.setUint8(offset + i, string.charCodeAt(i));
  }
}

const btnStyle = (bg) => ({
  background: bg, color: "white", padding: "10px 20px",
  border: "none", borderRadius: "5px", cursor: "pointer",
  display: "flex", alignItems: "center", gap: "10px", fontSize: "1rem"
});