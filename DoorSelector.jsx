import React from 'react';
import { DoorOpen } from 'lucide-react'; // אייקון של דלת

export default function DoorSelector({ onSelect }) {
  const rooms = [1, 2, 3, 4, 5, 6];

  return (
    <div style={{ textAlign: "center" }}>
      <h2 style={{color: "#333", marginBottom: "30px"}}>לאיזה חדר תרצי להיכנס?</h2>

      <div style={{
        display: "grid",
        gridTemplateColumns: "repeat(3, 1fr)",
        gap: "20px",
        maxWidth: "600px",
        margin: "0 auto"
      }}>
        {rooms.map((room) => (
          <button
            key={room}
            onClick={() => onSelect(room)}
            style={{
              background: "#ff8c00", // כתום
              border: "none",
              borderRadius: "15px",
              padding: "30px",
              cursor: "pointer",
              color: "white",
              fontSize: "1.5rem",
              boxShadow: "0 4px 6px rgba(0,0,0,0.1)",
              transition: "transform 0.2s"
            }}
            onMouseOver={(e) => e.currentTarget.style.transform = "scale(1.05)"}
            onMouseOut={(e) => e.currentTarget.style.transform = "scale(1)"}
          >
            <DoorOpen size={48} style={{display: "block", margin: "0 auto 10px"}}/>
            חדר {room}
          </button>
        ))}
      </div>
    </div>
  );
}