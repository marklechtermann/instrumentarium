# Instrumentarium

Android app (Kotlin, Jetpack Compose) for measurement and calibration on ECUs:
layouts with instruments, measuring, recording (MDF4), calibration with Data Sets.
Runs without hardware on a mock platform and talks to real devices via **XCP on Ethernet**.
See [PLAN.md](PLAN.md) for the plan and research.

## Features

| Area | Scope |
| --- | --- |
| Layouts | Any number of layouts (tabs), 12-column grid, edit mode: move, resize, duplicate, delete instruments, properties (double-tap or ✎). Export/import layouts as JSON. |
| Instruments (14) | Display, Gauge, Bar, Lamp, Multistate Display, Time Plotter, XY Plotter, Variable Array, Slider, Knob, Numeric Input, Push Button, Check Button, Static Text. Warning/alarm limits, colors, ranges, state tables. |
| Time Plotter | Multiple signals with individual Y axes, tap = pause, drag = measurement cursor. |
| Variables | Browser with search, measurement/parameter filter, live values, "place on layout as …", parameter writing. |
| Measuring | Online/offline, start/stop, polling raster 10–500 ms, 60 s ring buffer per signal. |
| Recorder | Immediate start or trigger (rising/falling/above/below) with pre-trigger and duration. Measurement data pool, offline view with zoom and cursor, export to **ASAM MDF 4.10** and CSV via the share menu. |
| Data Sets | Save parameter state, activate (write), compare with the current state. |
| Platform | Mock (built-in vehicle model) or XCP on Ethernet (TCP/UDP, Intel/Motorola) with A2L from the app, via URL or as a file. |
| Project | Autosave, project export/import, Operator mode (layouts locked). |

## Quick start without hardware

Start the app → tap ▶ at the top. The mock platform simulates a vehicle (engine, automatic transmission,
cooling, lateral dynamics, test signals) in a 60 s drive cycle. In the "Calibration" layout, switch off AutoDrive
and drive yourself with accelerator pedal, brake and steering wheel; blocking the fan + full throttle triggers overheating.

## Network device: XCP simulator

```bash
python3 simulator/xcp_sim.py          # XCP TCP+UDP :5555, A2L via http://<pc>:8080/demo.a2l
python3 simulator/xcp_sim.py -v       # additionally log calibration write accesses
python3 simulator/xcp_sim.py --big-endian --port 5556
```

Python standard library only. In the app: **Platform** → "XCP on Ethernet" → enter the PC's IP →
keep A2L "Demo A2L" (or load via URL) → **Go online** → ▶.
Phone and PC must be on the same network; if needed, open ports 5555/8080 in the PC firewall.
From the Android emulator, the host PC is reachable at `10.0.2.2`.

The XCP implementation (`core/xcp`) uses CONNECT, GET_STATUS, SHORT_UPLOAD (measuring via polling),
SET_MTA + DOWNLOAD (calibration) and reads MEASUREMENT/CHARACTERISTIC (VALUE) with COMPU_METHOD
IDENTICAL/LINEAR/RAT_FUNC/TAB_VERB from the A2L. This allows connecting any XCP-on-Ethernet slave with
a matching A2L, as long as it does not require Seed & Key. DAQ lists are the next expansion stage.

## Building

Open Android Studio (Ladybug or newer) → project folder → Run. Or from the command line:

```bash
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
```

Requirements: JDK 17+, Android SDK 35 (both ship with Android Studio).
`.github/workflows/android.yml` builds the APK in GitHub Actions once the project is in a repository.

## Tests without Android

`desktop-preview/` runs the platform-independent code (core + ui) on the JVM with Compose for Desktop:

```bash
cd desktop-preview && gradle test   # Gradle 8.x, JDK 21, python3 on PATH
```

- `CoreTest`: A2L parser, mock model, recorder trigger with pre-trigger, MDF4/CSV, project JSON
- `XcpIntegrationTest`: XCP client against `xcp_sim.py` over TCP, UDP and Motorola byte order, incl. calibration and error codes
- `ScreenshotTest`: renders all screens with live mock data to `build/screenshots/`

`desktop-preview/compile-check/` compiles the same sources against Compose Multiplatform 1.7.3, which matches
the API level of the Android build (Compose BOM 2024.12).

## Structure

```
app/src/main/java/de/instrumentarium/
  core/model     Project, Layout, Instrument, Data Set (kotlinx.serialization)
  core/source    DataSource, MockDataSource, VehicleModel, XcpDataSource
  core/xcp       XcpClient (TCP/UDP), A2lParser
  core/measure   MeasurementEngine, ring buffers, Recorder with trigger
  core/export    Mdf4Writer, CsvWriter
  ui/            Compose UI: App, instruments, screens, dialogs
  android/       MainActivity (saving, sharing, file picker)
simulator/       xcp_sim.py + generated demo.a2l
desktop-preview/ JVM tests, screenshots, compile check
```

## License

Copyright 2026 Mark Lechtermann

Licensed under the [Apache License, Version 2.0](LICENSE).
