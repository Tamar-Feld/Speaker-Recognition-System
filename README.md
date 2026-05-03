# 🛡️ SpeakerAuth: Biometric Access Control System

A state-of-the-art biometric access control system designed for classified facilities. 
SpeakerAuth utilizes Voice Recognition to grant or deny access in real-time. The system captures live audio, performs noise suppression, extracts spectral features (MFCC) via a custom DSP engine, and identifies the speaker using a Deep Learning model.

## 🏗️ Polyglot Architecture

The system is built using a micro-modular, polyglot architecture, ensuring that the "Best Tool for the Job" is used for each specific task:

* **Backend Orchestrator (Java / Spring Boot):** Acts as the central nervous system. It handles HTTP requests, orchestrates the C++ and Python sub-processes via `ProcessBuilder`, enforces business logic, and manages database transactions.
* **Database (MySQL):** A relational database ensuring ACID compliance. It manages user identities, room-specific access control (Authorization), and maintains a strict audit trail (Access Logs).
* **DSP Engine (C++):** A custom, dependency-free C++ engine. It parses binary WAV files, applies pre-emphasis, windowing, and performs Fast Fourier Transform (FFT) and MFCC extraction. Running close to the metal ensures low-latency signal processing.
* **AI & Denoising Engine (Python):** Handles the machine learning workload. It applies Spectral Gating for background noise suppression and utilizes Scikit-Learn (Deep Neural Network / Random Forest) to classify the extracted feature vectors and generate a Confidence Score.
* **Frontend (React.js):** A modern Single Page Application (SPA). It provides a secure admin dashboard (C5I) for user management/training, and simulated door-terminal interfaces that capture live microphone audio via the native Web Audio API.

## ⚙️ Data Flow Pipeline

1. **Ingestion:** The user records their voice at a specific door terminal via the browser.
2. **Preprocessing:** The Java backend receives the audio and passes it to Python for noise reduction, and then to C++ for mathematical feature extraction (outputting a feature vector).
3. **Inference:** The AI model evaluates the features and returns the predicted identity along with a confidence score.
4. **Authorization:** The Java backend verifies the confidence threshold, checks if the user exists in MySQL, and confirms they have permission for the requested room.
5. **Resolution:** The access log is securely recorded, and the client terminal displays either "ACCESS GRANTED" or "ACCESS DENIED".

## 🚀 Tech Stack

* **Core Backend:** Java 21, Spring Boot, Hibernate / JPA
* **Frontend:** React, Vite, Axios, Tailwind CSS, Lucide Icons
* **Database:** MySQL
* **Signal Processing:** Vanilla C++ (Custom FFT implementation)
* **Machine Learning:** Python, Scikit-Learn, NumPy, Joblib
