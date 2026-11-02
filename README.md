# UrbanPulse – AI-Powered Smart Civic Intelligence Platform

UrbanPulse is an end-to-end civic grievance reporting, intelligent triage, and resolution platform. It connects citizens with municipal administration through a native Android mobile application, a responsive web dashboard for municipal staff, an AI-powered image classification microservice, and a Spring Boot backend.

The platform digitizes and automates the civic issue redressal lifecycle:
1. **Citizens** capture live on-site photographs and GPS coordinates using their smartphones.
2. An integrated **Convolutional Neural Network (MobileNetV2)** analyzes the image and classifies the defect into one of eight civic grievance categories.
3. If the model's confidence exceeds **50%** and an active supervisor is available, the complaint is **automatically forwarded** to the responsible department's supervisor.
4. If confidence is 50% or below, the category is unsupported, or no supervisor exists, the complaint safely routes to the **Municipal Administrator** for manual review.
5. Department **Supervisors** review departmental queues and assign field workers.
6. Field **Workers** execute the repair and upload mandatory photographic resolution proof before marking tickets resolved.
7. Automated **Firebase Cloud Messaging (FCM)** push notifications keep citizens and staff updated in real time.

---

## Table of Contents

1. [Project Overview](#project-overview)
2. [Features](#features)
3. [Technology Stack](#technology-stack)
4. [Project Folder Structure](#project-folder-structure)
5. [Prerequisites and Software Requirements](#prerequisites-and-software-requirements)
6. [Installation and Setup](#installation-and-setup)
7. [PostgreSQL Database Setup](#postgresql-database-setup)
8. [ML Model and Flask Service Setup](#ml-model-and-flask-service-setup)
9. [Spring Boot Backend Setup](#spring-boot-backend-setup)
10. [React Staff Portal Setup](#react-staff-portal-setup)
11. [React Native Citizen App Setup](#react-native-citizen-app-setup)
12. [How to Run the Entire Application (5 CMD Windows)](#how-to-run-the-entire-application-5-cmd-windows)
13. [ML Classification and Automatic Routing Engine](#ml-classification-and-automatic-routing-engine)
14. [API Configuration and Environment Variables](#api-configuration-and-environment-variables)
15. [Troubleshooting Guide](#troubleshooting-guide)
16. [GitHub Repository Information](#github-repository-information)

---

## 1. Project Overview

Municipal grievance redressal often suffers from manual data entry delays, inaccurate defect categorization, lost complaints, and lack of accountability. UrbanPulse solves these challenges through:
- **Zero-Guesswork Citizen Reporting**: Citizens take a photo and hardware GPS coordinates are automatically recorded.
- **Automated AI Routing**: Deep-learning image classification removes manual dispatch delays by mapping defects straight to the relevant department supervisor.
- **Strict SLA Enforcement**: Dynamic deadlines (e.g., 6h for water leaks, 12h for garbage, 24h for potholes, 48h for infrastructure) are computed automatically on ticket creation.
- **Fail-Safe Operation**: ML service downtimes or ambiguous images never drop a complaint; tickets fall back gracefully to the Municipal Administrator.
- **Duplicate Detection & Joining**: Within a 30-meter radius, citizens can view and join existing complaints rather than creating duplicate tickets.
- **Mandatory Visual Proof of Resolution**: Workers cannot resolve a ticket without uploading photographic proof of the finished repair.

---

## 2. Features

- **Citizen Mobile Application**:
  - Secure phone number verification via Firebase SMS OTP.
  - Camera capture and high-accuracy GPS geocoding.
  - Real-time duplicate detection within a 30-meter radius.
  - One-tap "Join Issue" functionality with live participation counts.
  - Complaint timeline tracking with SLA status badges.
- **Automated ML Routing**:
  - Pretrained MobileNetV2 8-class classification model (`urbanpulse_8class_pothole_stage1.keras`).
  - Strict 50% confidence threshold (`confidence > 0.50`).
  - Automatic department mapping and active supervisor lookup.
- **Staff Web Portal**:
  - **Admin View**: Triage incoming tickets, review low-confidence AI predictions, reassign departments, register departments, and create staff accounts.
  - **Supervisor View**: Departmental queue view, task assignment to workers, review resolution photos, re-routing.
  - **Worker View**: Personal task list, Google Maps direct navigation link, progress toggles, mandatory resolution photo upload.
- **Security & Data Integrity**:
  - Role-Based Access Control (RBAC) supporting `CITIZEN`, `ADMIN`, `SUPERVISOR`, and `WORKER`.
  - Stateless JWT token verification and Spring Security 6 filter chain.
  - Status history audit logging recording every transition, actor, and timestamp.

---

## 3. Technology Stack

| Layer | Component | Technologies |
|---|---|---|
| **Citizen App** | Mobile Client | React Native 0.82+, TypeScript, React Native Geolocation, Firebase Auth |
| **Staff Portal** | Web Client | React 18, Vite 5, Lucide React, Tailwind CSS / Vanilla CSS |
| **Backend API** | REST Server | Java 17 LTS, Spring Boot 3.3.4, Spring Data JPA, Spring Security, Hibernate 6.5 |
| **AI / ML Service** | Inference Engine | Python 3.10+, Flask 3.1, TensorFlow 2.21+, Keras, NumPy, Pillow |
| **Database** | Relational DB | PostgreSQL (v14/15/16/18+), HikariCP Connection Pool |
| **Push Notifications** | Messaging | Firebase Cloud Messaging (FCM) & Firebase Admin Java SDK |

---

## 4. Project Folder Structure

```text
C:\Users\HP\Urban Pulse\
├── ml-resources\
│   └── CivicComplaintML\
│       ├── models\
│       │   ├── urbanpulse_8class_pothole_stage1.keras    # 8-Class MobileNetV2 Model
│       │   └── urbanpulse_8class_class_names.json        # Class name order
│       ├── urbanpulse_ai\
│       │   ├── app.py                                    # Flask Inference Microservice
│       │   └── requirements.txt                          # Python dependencies
│       ├── requirements.txt
│       └── run_ml_service.bat                            # Windows batch startup script
│
├── Urban-Pulse\                                          # Spring Boot 3 Backend
│   ├── src\
│   │   ├── main\
│   │   │   ├── java\com\urbanpulse\
│   │   │   │   ├── controller\                           # REST Controllers
│   │   │   │   ├── dto\                                  # Data Transfer Objects
│   │   │   │   ├── entity\                               # JPA Entities (Complaint, User, etc.)
│   │   │   │   ├── exception\                            # Global Exception Handlers
│   │   │   │   ├── repository\                           # Spring Data Repositories
│   │   │   │   └── service\                              # Business & ML Routing Logic
│   │   │   └── resources\
│   │   │       └── application.properties                # Spring Boot Configuration
│   │   └── test\                                         # JUnit 5 & Mockito Test Suites
│   ├── uploads\                                          # Stored Complaint & Resolution Images
│   ├── pom.xml                                           # Maven dependencies
│   └── urbanpulse-firebase-admin.json                    # Firebase Admin SDK Credentials
│
├── Urban-Pulse-FrontEnd\                                 # Citizen Mobile & Web Dashboards
│   ├── android\                                          # Native Android Project
│   │   └── app\
│   │       └── google-services.json                      # Firebase Android Client Config
│   ├── src\
│   │   ├── config\api.ts                                 # Mobile Backend API Base URL
│   │   └── services\complaintService.ts                  # Mobile API Client
│   ├── staff-web\                                        # Vite React Staff Web Portal
│   │   ├── src\
│   │   │   ├── components\
│   │   │   │   ├── AdminView.jsx                         # Admin Dashboard
│   │   │   │   ├── SupervisorView.jsx                    # Supervisor Dashboard
│   │   │   │   └── WorkerView.jsx                        # Worker Dashboard
│   │   │   └── services\apiService.js                    # Web Portal API Client
│   │   ├── index.html
│   │   ├── package.json
│   │   └── vite.config.js
│   ├── App.tsx                                           # React Native Main Component
│   └── package.json                                      # Mobile dependencies
│
└── README.md                                             # This Documentation File
```

---

## 5. Prerequisites and Software Requirements

Ensure the following tools are installed on your Windows machine:

| Software | Required Version | Purpose | Verify Command |
|---|---|---|---|
| **Git** | `2.x+` | Source code management | `git --version` |
| **Java JDK** | `17 LTS` | Compile and run Spring Boot | `java -version` |
| **Apache Maven** | `3.8+` | Build backend dependencies | `mvn -version` |
| **Python** | `3.10 – 3.13` | Run Flask ML inference engine | `python --version` |
| **PostgreSQL** | `14+` / `18+` | Relational database | `psql -U postgres -V` |
| **Node.js** | `>= 20.x` / `22.x` | Metro bundler and Vite server | `node -v` |
| **npm** | `10.x+` | Package manager | `npm -v` |
| **Android Studio** | Ladybug (`2024.2+`) | Android SDK & Emulator | Studio UI |

---

## 6. Installation and Setup

1. Open Windows Command Prompt (`cmd`) and clone the repository:
   ```cmd
   git clone https://github.com/vishnuvardhan1495/UrbanPulse.git "C:\Users\HP\Urban Pulse"
   cd "C:\Users\HP\Urban Pulse"
   ```

2. Switch to the active development branch:
   ```cmd
   git checkout feature/urbanpulse-ml-integration
   ```

---

## 7. PostgreSQL Database Setup

1. Open an **Administrator Command Prompt** and start the PostgreSQL Windows service:
   ```cmd
   net start postgresql-x64-18
   ```
2. Verify that PostgreSQL is running:
   ```cmd
   sc query postgresql-x64-18
   ```
   *(Status should display `RUNNING`).*

3. Create the `urbanpulse` database using `psql`:
   ```cmd
   psql -U postgres -c "CREATE DATABASE urbanpulse;"
   ```
   *(Enter your PostgreSQL password when prompted. Default password configured in application properties is `root123`).*

4. Verify database creation:
   ```cmd
   psql -U postgres -d urbanpulse -c "\l"
   ```

> **Note:** Hibernate automatically manages database schemas on startup (`spring.jpa.hibernate.ddl-auto=update`).

---

## 8. ML Model and Flask Service Setup

The ML service uses a MobileNetV2 neural network trained on civic issues with 8 defect classes.

1. Navigate to the ML service directory:
   ```cmd
   cd "C:\Users\HP\Urban Pulse\ml-resources\CivicComplaintML"
   ```
2. Install Python dependencies:
   ```cmd
   pip install -r requirements.txt
   ```
   *(Ensure `tensorflow`, `flask`, `pillow`, `numpy`, and `requests` are installed).*
3. Verify that the model and class definitions exist:
   - Model file: `models\urbanpulse_8class_pothole_stage1.keras`
   - Class file: `models\urbanpulse_8class_class_names.json`
4. Start the service (see [Step-by-step Startup](#cmd-2-flask-ml-inference-service-port-5001)).

---

## 9. Spring Boot Backend Setup

1. Open `Urban-Pulse\src\main\resources\application.properties` and verify your local configurations:
   ```properties
   # Server Port
   server.port=8080
   server.address=0.0.0.0

   # Database Configuration
   spring.datasource.url=jdbc:postgresql://localhost:5432/urbanpulse
   spring.datasource.username=postgres
   spring.datasource.password=root123

   # JPA / Hibernate
   spring.jpa.hibernate.ddl-auto=update
   spring.jpa.show-sql=false

   # Firebase Service Account
   firebase.credentials.path=urbanpulse-firebase-admin.json

   # File Upload Limits
   spring.servlet.multipart.max-file-size=25MB
   spring.servlet.multipart.max-request-size=30MB

   # ML Service URL and Threshold
   urbanpulse.ml.service-url=http://localhost:5001
   urbanpulse.ml.confidence-threshold=0.50
   urbanpulse.ml.timeout-ms=8000
   ```

2. Place your `urbanpulse-firebase-admin.json` file inside `Urban-Pulse\`.

3. Build and execute backend tests:
   ```cmd
   cd "C:\Users\HP\Urban Pulse\Urban-Pulse"
   mvn clean test
   ```
   *(All 101 tests should pass).*

---

## 10. React Staff Portal Setup

1. Navigate to the staff web directory:
   ```cmd
   cd "C:\Users\HP\Urban Pulse\Urban-Pulse-FrontEnd\staff-web"
   ```
2. Install frontend dependencies:
   ```cmd
   npm install
   ```
3. Verify the production build:
   ```cmd
   npm run build
   ```
4. Start the development server on port **`5173`**:
   ```cmd
   npm run dev
   ```

---

## 11. React Native Citizen App Setup

1. Navigate to the mobile app directory:
   ```cmd
   cd "C:\Users\HP\Urban Pulse\Urban-Pulse-FrontEnd"
   ```
2. Install dependencies:
   ```cmd
   npm install
   ```
3. Ensure your Firebase configuration file exists at:
   ```text
   Urban-Pulse-FrontEnd\android\app\google-services.json
   ```
4. Configure your machine's LAN IP in `Urban-Pulse-FrontEnd\src\config\api.ts`:
   ```typescript
   export const API_BASE_URL = 'http://192.168.x.x:8080';
   ```
   *(Run `ipconfig` in Command Prompt to obtain your local IPv4 address. Do **not** use `localhost` when testing on a physical mobile phone).*

---

## 12. How to Run the Entire Application (5 CMD Windows)

Open **5 separate Windows Command Prompt windows**. Keep all 5 windows running while testing.

```text
┌────────────────┐  ┌────────────────┐  ┌────────────────┐  ┌────────────────┐  ┌────────────────┐
│     CMD 1      │  │     CMD 2      │  │     CMD 3      │  │     CMD 4      │  │     CMD 5      │
│   PostgreSQL   │  │    Flask ML    │  │  Spring Boot   │  │   Staff Web    │  │  Citizen App   │
│   Port: 5432   │  │   Port: 5001   │  │   Port: 8080   │  │   Port: 5173   │  │   Port: 8081   │
└────────────────┘  └────────────────┘  └────────────────┘  └────────────────┘  └────────────────┘
```

---

### CMD 1: PostgreSQL Database (Port 5432)
*Note: Run Command Prompt as Administrator.*
```cmd
net start postgresql-x64-18
```
**Verification:**
```cmd
sc query postgresql-x64-18
```
Output must show `STATE: 4 RUNNING`.

---

### CMD 2: Flask ML Inference Service (Port 5001)
```cmd
cd "C:\Users\HP\Urban Pulse"
python "ml-resources\CivicComplaintML\urbanpulse_ai\app.py"
```
**Expected Output:**
```text
[INFO] Loading model from: ...\urbanpulse_8class_pothole_stage1.keras
[INFO] Keras model loaded successfully!
[INFO] Loaded 8 classes: ['Damaged concrete structures', ...]
* Running on http://127.0.0.1:5001
* Running on http://192.168.x.x:5001
```
**Verification:**
Open `http://localhost:5001/health` in your browser. Expected response:
```json
{
  "classes": ["Damaged concrete structures", "DamagedElectricalPoles", "DamagedRoadSigns", "DeadAnimalsPollution", "FallenTrees", "Garbage", "IllegalParking", "Potholes and RoadCracks"],
  "input_dimensions": [224, 224, 3],
  "input_shape": [null, 224, 224, 3],
  "model_loaded": true,
  "model_name": "urbanpulse_8class_pothole_stage1.keras",
  "model_version": "urbanpulse_8class_pothole_stage1",
  "num_classes": 8,
  "status": "ok"
}
```

---

### CMD 3: Spring Boot Backend (Port 8080)
```cmd
cd "C:\Users\HP\Urban Pulse\Urban-Pulse"
mvn spring-boot:run
```
**Expected Output:**
```text
Started UrbanPulseApplication in ... seconds
[INFO] MlInferenceService initialized with URL: http://localhost:5001 and timeout: 8000ms
```
**Verification:**
Open `http://localhost:8080/api/test` in your browser. Expected response:
```text
UrbanPulse Backend is running
```

---

### CMD 4: React Staff Portal (Port 5173)
```cmd
cd "C:\Users\HP\Urban Pulse\Urban-Pulse-FrontEnd\staff-web"
npm run dev
```
**Expected Output:**
```text
VITE v5.4.x ready in ... ms
➜  Local:   http://localhost:5173/
```
**Verification:**
Open **`http://localhost:5173`** in your browser. The Staff Portal login page should display.

---

### CMD 5: React Native Citizen App (Metro Bundler: Port 8081)
```cmd
cd "C:\Users\HP\Urban Pulse\Urban-Pulse-FrontEnd"
npm start
```

To run the Android app on an emulator or connected phone:
- In the running Metro window, press **`a`**, **OR**
- Open a helper CMD window and run:
  ```cmd
  cd "C:\Users\HP\Urban Pulse\Urban-Pulse-FrontEnd"
  npx react-native run-android
  ```

---

## 13. ML Classification and Automatic Routing Engine

UrbanPulse classifies civic complaints into **8 categories** mapped across **7 departments**:

| No. | Complaint Category | Responsible Department |
|:---:|---|---|
| 1 | **Damaged Concrete Structures** | Engineering Department |
| 2 | **Damaged Electrical Poles** | Electrical Department |
| 3 | **Damaged Road Signs** | Road Safety Department |
| 4 | **Dead Animals Pollution** | Public Health Department |
| 5 | **Fallen Trees** | Horticulture Department |
| 6 | **Garbage** | Sanitation Department |
| 7 | **Illegal Parking** | Traffic Department |
| 8 | **Potholes and Road Cracks** | Road Safety Department |

> **Important Architecture Note:**  
> Categories **3 (Damaged Road Signs)** and **8 (Potholes and Road Cracks)** both route to **Road Safety Department** and share its active supervisor, but remain distinct issue types (`INFRASTRUCTURE_DAMAGE` vs `POTHOLE`) with separate SLA calculation and duplicate tracking.

### Routing Logic:
1. **Confidence > 50% & Active Supervisor Exists**:
   - Status: `FORWARDED`
   - Assignment Method: `AUTO_ML`
   - Automatically assigned to the department's active supervisor.
   - Push notifications sent to citizen and supervisor.
2. **Confidence $\le$ 50%**:
   - Status: `SUBMITTED`
   - Assignment Method: `MANUAL_REVIEW_LOW_CONFIDENCE`
   - Routed to Municipal Admin for manual review.
3. **No Active Supervisor for Department**:
   - Status: `SUBMITTED`
   - Assignment Method: `MANUAL_REVIEW_NO_SUPERVISOR`
   - Routed to Municipal Admin to assign a supervisor.
4. **Unsupported Category**:
   - Status: `SUBMITTED`
   - Assignment Method: `MANUAL_REVIEW_UNSUPPORTED_CATEGORY`
   - Routed to Municipal Admin.
5. **ML Service Unavailable / Timeout**:
   - Status: `SUBMITTED`
   - Assignment Method: `MANUAL_REVIEW_ML_FAILED`
   - Complaint is **safely created** without data loss and forwarded to Admin.

> **Worker Assignment Rule:** Workers are **never** automatically assigned by AI. The department supervisor retains sole authority to review the complaint and assign a field worker.

---

## 14. API Configuration and Environment Variables

### Backend Configuration (`Urban-Pulse\src\main\resources\application.properties`)
- `spring.datasource.url`: JDBC database URL (`jdbc:postgresql://localhost:5432/urbanpulse`).
- `spring.datasource.username`: PostgreSQL user (default: `postgres`).
- `spring.datasource.password`: PostgreSQL password (e.g., `root123`).
- `urbanpulse.ml.service-url`: URL of Flask ML service (`http://localhost:5001`).
- `urbanpulse.ml.confidence-threshold`: Float threshold (`0.50`).

### Mobile App Configuration (`Urban-Pulse-FrontEnd\src\config\api.ts`)
```typescript
export const API_BASE_URL = 'http://<YOUR_LAPTOP_IP>:8080';
```
- Physical Device: Use computer's local Wi-Fi IP (e.g., `http://192.168.29.21:8080`).
- Android Emulator: Use `http://10.0.2.2:8080`.

### Staff Web Portal Configuration (`Urban-Pulse-FrontEnd\staff-web\src\services\apiService.js`)
```javascript
const API_BASE_URL = 'http://localhost:8080';
```

---

## 15. Troubleshooting Guide

### 1. PostgreSQL Access Denied / Service Not Found
- **Symptom:** `System error 5 has occurred. Access is denied.`
  - **Fix:** Open Command Prompt with **Run as Administrator**.
- **Symptom:** `The service name is invalid.`
  - **Fix:** Verify your installed PostgreSQL service name:
    ```cmd
    powershell -Command "Get-Service *postgres*"
    ```
    Use the exact name returned (e.g., `net start postgresql-x64-18`).

### 2. Flask ML Service Fails to Start
- **Symptom:** `ModuleNotFoundError: No module named 'tensorflow'`
  - **Fix:** Install dependencies using the appropriate Python environment:
    ```cmd
    pip install tensorflow flask pillow numpy requests
    ```
- **Symptom:** `FileNotFoundError: urbanpulse_8class_pothole_stage1.keras`
  - **Fix:** Start Flask from the repository root:
    ```cmd
    cd "C:\Users\HP\Urban Pulse"
    python "ml-resources\CivicComplaintML\urbanpulse_ai\app.py"
    ```

### 3. Port 5001 or 8080 Already in Use
- **Symptom:** `Address already in use: bind`
  - **Fix:** Identify and terminate the occupying PID:
    ```cmd
    netstat -ano | findstr :8080
    taskkill /F /PID <PID_NUMBER>
    ```

### 4. Spring Boot Database Connection Errors
- **Symptom:** `Connection to localhost:5432 refused` or `FATAL: password authentication failed`
  - **Fix:**
    1. Ensure PostgreSQL is started (`net start postgresql-x64-18`).
    2. Check password in `application.properties` matches your PostgreSQL `postgres` user password.
    3. Ensure `urbanpulse` database is created (`CREATE DATABASE urbanpulse;`).

### 5. Physical Mobile Phone Cannot Connect to Backend
- **Symptom:** `Unable to submit complaint. Connection timed out.`
  - **Fix:**
    1. Phone and PC must be on the **same Wi-Fi network**.
    2. Ensure `API_BASE_URL` in `Urban-Pulse-FrontEnd\src\config\api.ts` uses your computer's LAN IP (`192.168.x.x`), **not** `localhost`.
    3. Allow inbound connections on port 8080 in Windows Firewall:
       ```cmd
       netsh advfirewall firewall add rule name="Spring Boot 8080" dir=in action=allow protocol=TCP localport=8080
       ```

### 6. Android Build or `npm` Errors
- **Symptom:** Metro cache conflicts or stale bundles.
  - **Fix:** Start Metro with cache reset:
    ```cmd
    cd "C:\Users\HP\Urban Pulse\Urban-Pulse-FrontEnd"
    npm start -- --reset-cache
    ```
- **Symptom:** `adb devices` returns an empty list.
  - **Fix:** Enable **USB Debugging** on your phone, reconnect the cable, and tap **Allow USB Debugging** on the phone screen.

---

## 16. GitHub Repository Information

- **Repository:** [https://github.com/vishnuvardhan1495/UrbanPulse](https://github.com/vishnuvardhan1495/UrbanPulse)
- **Active Branch:** `feature/urbanpulse-ml-integration`
- **Primary Branch:** `main`

### Contributing & Pushing Changes:
```cmd
git status
git add README.md
git commit -m "docs: update UrbanPulse setup and startup instructions"
git push origin feature/urbanpulse-ml-integration
```
