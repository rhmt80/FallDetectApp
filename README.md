# FallDetectApp

An Android app that detects falls using on-device ML inference and phone sensors, then sends an SMS alert with location to a configured caretaker.

## How It Works

The app runs a foreground service that continuously monitors accelerometer and gyroscope data. When a potential fall is detected, it goes through a **4-gate validation pipeline** to eliminate false positives:

1. **Impact Gate** — Peak accelerometer magnitude must exceed 25 m/s² (~2.5g)
2. **Free-Fall Gate** — A dip below 6 m/s² must precede the impact (the brief weightlessness before hitting the ground)
3. **ML Gate** — A TensorFlow Lite model must score the sensor window ≥ 0.92 confidence
4. **Stillness Gate** — Post-impact accelerometer data must show the person is stationary for ~2.5 seconds (rules out trips where the person recovers)

Only when all four gates pass does the app trigger an alert.

## Alert Flow

1. Phone vibrates and plays an alarm sound
2. A 15-second countdown gives the user time to cancel (false alarm)
3. If not cancelled, an SMS is sent to the caretaker with:
   - User's name and phone number
   - Google Maps link to last known location

## Features

- **Two monitoring modes:**
  - **Constant** — Sensors always at high rate (~50 Hz). Most reliable.
  - **Adaptive** — Starts at low rate, switches to high rate on significant motion. Saves battery.
- **Sliding window inference** — Overlapping sensor windows ensure falls at window boundaries aren't missed
- **3-minute cooldown** between alerts to prevent repeated firing
- **Setup wizard** for configuring user and caretaker details
- **Test Alert button** to verify SMS delivery without simulating a fall

## Architecture

```
ForegroundSensorService    Main service — sensor listener, pipeline orchestrator, alert dispatch
├── SensorBuffer           Sliding window of accelerometer + gyroscope samples
├── FallValidator          Physics-based gates (free-fall, impact, stillness)
├── TFLiteRunner           TensorFlow Lite model inference
├── WakeDetector           Adaptive mode: gates LOW↔HIGH rate switching
├── MetricsLogger          Tracks sensor events and inference counts
└── AppStatusNotifier      Persistent status notification
```

## Requirements

- Android 8.0+ (API 26)
- Permissions: SMS, Location, Notifications, Body Sensors

## Building

```bash
./gradlew assembleDebug
```

The TFLite model file (`model.tflite`) must be placed in `app/src/main/assets/`.

## Testing on Device

Monitor the detection pipeline in real-time:

```bash
adb logcat -s INFERENCE:D ALERT:D ADAPTIVE:D MODE:D
```

| Log tag | What it shows |
|---------|---------------|
| `INFERENCE` | Gate decisions, confidence scores, stillness results |
| `ALERT` | SMS sent/failed, cooldown, cancel events |
| `ADAPTIVE` | Rate switching (HIGH/LOW) |
| `MODE` | Service start mode (ADAPTIVE or CONSTANT) |

## Tech Stack

- Kotlin
- TensorFlow Lite 2.12
- Android Foreground Service
- SensorManager (Accelerometer + Gyroscope)
- SmsManager for alert delivery
- LocationManager for GPS coordinates
