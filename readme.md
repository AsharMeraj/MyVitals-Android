# Child Life Vitals System 🩺

> A full-stack IoT solution for real-time pediatric vital sign monitoring — built for the bedside, designed for care.

![Next.js](https://img.shields.io/badge/Next.js-000000?style=flat-square&logo=nextdotjs&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-7F52FF?style=flat-square&logo=kotlin&logoColor=white)
![Firebase](https://img.shields.io/badge/Firebase-FFCA28?style=flat-square&logo=firebase&logoColor=black)
![TypeScript](https://img.shields.io/badge/TypeScript-3178C6?style=flat-square&logo=typescript&logoColor=white)
![Android](https://img.shields.io/badge/Android-3DDC84?style=flat-square&logo=android&logoColor=white)
![License](https://img.shields.io/badge/License-MIT-blue?style=flat-square)

---

## 📋 Table of Contents

- [Overview](#overview)
- [Patient Dashboard and the Data Coming from Cardiac Monitor](#patient-dashboard-and-the-data-coming-from-cardiac-monitor)
- [Wristband Connection](#wristband-connection)
- [Vitals From Wristband](#vitals-from-wristband)
- [Tech Stack](#tech-stack)
- [License](#license)

---

## Overview

This project is two things in one: a Next.js web dashboard and a native Android app, built to work together as a single system for monitoring children's vital signs in real time. The Android app runs in kiosk mode on a dedicated wristband device, collects vitals over Bluetooth, and pipes them into the web app — which displays everything live through a Firebase-backed API. I built this to bridge the gap between wearable IoT hardware and a clean, accessible healthcare interface.

---

## 📊 Patient Dashboard and the Data Coming from Cardiac Monitor

This is the heart of the web application. The dashboard pulls in live data from the cardiac monitor and presents it in a clear, card-based layout that makes it easy to read vitals at a glance. Each vital sign is displayed in its own card with formatted timestamps so you always know how fresh the data is. Firebase sits behind the scenes, handling data storage and keeping everything in sync without any manual refreshing needed.

- Displays real-time vital signs streamed from the cardiac monitor through the web API
- Each VitalCard component updates independently, so the UI stays clean even when data comes in at different intervals

<p align="center">
  <img src="/public/assets/PatientDashboard.png" width="500"/>
</p>

---

## 📡 Wristband Connection

The Android app handles the Bluetooth pairing flow with the wristband device. When the app boots — in full kiosk mode, so nothing else gets in the way — it scans for the paired wristband and establishes a Bluetooth connection automatically. If the internet drops or the connection times out, the app has a native no-internet screen that prompts a retry rather than leaving the display blank. The whole thing is designed to run unattended on a dedicated device.

- Bluetooth connection is handled natively in Kotlin, with the `KioskBridgeHandler` managing the communication channel between the WebView and native Android code
- Kiosk mode locks the device to this app only, which is exactly what you want when it's mounted at a patient's bedside

<p align="center">
  <img src="/public/assets/WristbandConnection.png" width="500"/>
</p>

---

## ❤️ Vitals From Wristband

Once the wristband is connected, `VitalsService` runs as a background service collecting sensor data over Bluetooth and sending it up to the Next.js API endpoint. That data hits Firebase, and the web dashboard picks it up almost instantly. The Android app embeds the web app in a WebView, so the same real-time vitals display you'd see on a desktop shows up right on the wristband device — no duplicate UI needed.

- The `Bridge.ts` on the web side and `KioskBridgeHandler.kt` on the Android side work together so native and JavaScript code can talk to each other when needed
- Vital readings from the wristband sensors are formatted and timestamped before display, keeping the data readable and traceable

<p align="center">
  <img src="/public/assets/WristbandVitals.png" width="500"/>
</p>

---

## 🛠 Tech Stack

| Layer | Technology |
|---|---|
| Web Frontend | Next.js, React, TypeScript |
| Backend / API | Next.js API Routes |
| Database | Firebase (Firestore / Realtime Database) |
| Auth / Cloud | Firebase Admin SDK |
| Mobile | Android (Kotlin) |
| UI (Android) | Jetpack Compose, WebView |
| IoT / Hardware | Bluetooth Wristband Integration |
| Styling | Tailwind CSS, PostCSS |


---

<p align="center">Built with care for the kids who need it most 💙</p>