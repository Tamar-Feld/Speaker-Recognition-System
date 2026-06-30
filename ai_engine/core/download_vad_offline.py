import torch
import os

print("Downloading Silero VAD for OFFLINE use...")

# הוספנו trust_repo=True כדי לעקוף את חסימת האבטחה של PyTorch
model, utils = torch.hub.load(
    repo_or_dir='snakers4/silero-vad',
    model='silero_vad',
    force_reload=True,
    trust_repo=True
)

# שמירת המודל כקובץ JIT מקומי
torch.jit.save(model, "silero_vad_offline.jit")
print("✅ SUCCESS! Saved as 'silero_vad_offline.jit'.")