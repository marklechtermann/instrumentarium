# Instrumentarium – Plan

Android app (Kotlin, Jetpack Compose) that brings the operating concept of classic measurement and calibration tools
to tablets and smartphones: layouts with instruments, measuring, recording,
calibration. It runs without hardware and without VEOS/SIL using a built-in
mock data source, and talks to a real measurement device over the network via **XCP on Ethernet**.

## 1. What makes a measurement and calibration tool (research)

| Area | Desktop tool | Relevance for the app |
| --- | --- | --- |
| Project / Experiment | Projects with experiments, platforms/devices, layouts, Data Sets | One project with layouts, platform configuration, Data Sets (JSON) |
| Layouts & Instruments | Free-form layouts; instruments such as Display, Gauge, Bar, Lamp, Multistate Display, Plotter (Time/XY), Slider, Knob, Numeric Input, Push/Check/Radio Button, Variable Array, Static Text | Grid layouts with 14 instrument types, edit mode (move, resize, properties) |
| Variables | Variables panel from A2L/SDF/XIL mapping, drag & drop onto instruments | Variable browser with search, live value, "place on layout" |
| Measuring | Go Online → Start Measuring; Measurement Configuration with rasters (polling, DAQ events) | Online/offline, start/stop, selectable raster (10–500 ms) |
| Recording | Recorder with start trigger, pre-trigger, duration; Measurement Data Pool; export ASAM MDF 4.1 | Recorder with trigger (rising/falling/above/below), pre-trigger, duration; data pool; export **MDF4** and CSV |
| Analysis | Plotter with zoom, cursor, multiple Y axes | Offline viewer with zoom/pan and measurement cursor |
| Calibration | Write parameters, Data Sets (Working/Reference), compare | Write parameters via slider/input, save/activate/compare Data Sets |
| Devices/Protocols | XCP on CAN/Ethernet, CCP, VEOS, SCALEXIO, Bus Navigator | **XCP on Ethernet (TCP/UDP) + A2L**, mock platform |
| Operation protection | Operator Mode | Operator mode: layouts locked, operation only |

Sources: product documentation of common measurement and calibration tools, HBM tech note
"QuantumX via XCP on Ethernet", FAQ on recorder triggers.

### Use cases for the app

1. **Demo/training without hardware**: mock vehicle model, ready-made layouts (Cockpit, Calibration, Analysis).
2. **Mobile cockpit at the test bench / in the vehicle**: tablet shows key signals of an ECU/HIL over Wi-Fi.
3. **Quick calibration in the field**: change parameters via slider, see the effect live, save the state as a Data Set.
4. **Event recording**: trigger on a signal (e.g. coolant > 105 °C), pre-trigger, share MDF4.
5. **Layout exchange**: export/import layouts as JSON and share them within the team.

## 2. Network device: decision

**Chosen: XCP on Ethernet (ASAM MCD-1 XCP, TCP or UDP) with A2L description.**

- This is exactly how measurement and calibration tools talk to ECUs, measurement modules (e.g. HBM QuantumX)
  and virtual ECUs. Whatever works with the simulator works
  with any XCP slave that has an A2L.
- Measuring via **polling** (`SHORT_UPLOAD`), calibration via `SET_MTA` + `DOWNLOAD`.
  DAQ lists (event-driven measurement) are the next expansion stage.
- Alternatives rejected: MQTT/WebSocket would be simpler but proprietary and not
  industry standard; Modbus TCP belongs to industrial automation, not the measurement/calibration world.

Included is `simulator/xcp_sim.py` (Python standard library only):
an XCP slave on port 5555 (TCP+UDP) that computes the same vehicle model as the mock source,
plus HTTP on port 8080 serving the matching `demo.a2l`.

## 3. Architecture

```
app/src/main/java/de/instrumentarium/
  core/        pure Kotlin (no Android): model, data sources, XCP, A2L, Recorder, MDF4
    model/       Project, Layout, Instrument, DataSet (kotlinx.serialization)
    source/      DataSource interface, MockDataSource, VehicleModel, XcpDataSource
    xcp/         XcpClient (TCP/UDP transport, commands), A2lParser
    measure/     MeasurementEngine (polling raster, ring buffers), Recorder, Trigger
    export/      Mdf4Writer, CsvWriter
  ui/          Compose UI (platform-independent)
    instruments/ one composable per instrument
    screens/     Layouts, Variables, Recorder/data pool, Data Sets, Platform
  MainActivity.kt, AndroidPlatform.kt  (saving, sharing, file picker)
simulator/     xcp_sim.py, demo.a2l
desktop-preview/  Gradle build that compiles core+ui with Compose Desktop,
                  renders screenshots and tests the XCP client against the simulator
```

- **MeasurementEngine** polls, at the selected raster, all variables used on layouts or in the recorder,
  writes them to ring buffers (60 s) and publishes a snapshot flow.
- **Instruments** are data-driven (`Instrument` record with type, grid position,
  variables, min/max, thresholds, states) and can therefore be stored and shared as JSON.
- Persistence: project JSON in app storage, saved automatically.

## 4. Implementation steps

1. Core: model, mock vehicle model, MeasurementEngine, Recorder with trigger.
2. XCP client + A2L parser, Python simulator, integration test client↔simulator.
3. MDF4 writer, validated with `asammdf`.
4. UI: navigation, layout editor, 14 instruments, variable browser, Recorder/data pool,
   offline viewer, Data Sets, platform settings, Operator mode.
5. Desktop preview: compile, screenshots, tests.
6. Android build (Gradle/AGP) and GitHub Actions workflow for the APK.

## 5. Next expansion stages

- XCP DAQ lists (event-driven, higher rates), Seed & Key
- XIL API/VEOS connection via a gateway, Signal Editor (stimulus)
- Multiple platforms simultaneously, synchronized time base
- Curves/maps (CURVE/MAP) with table editor
