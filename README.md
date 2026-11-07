# rtl-df-android · Radio direction finding for Android (Kotlin)

Android app for locating transmitters with a Yagi antenna. The phone is mounted on the Yagi and acts as screen, GPS and compass; the RTL-SDR connects over USB OTG and is served by the **SDR driver** app (`rtl_tcp` on `127.0.0.1:1234`).

**Workflow**
1. **Scan**: sweeps 400–470 MHz (the range of the Ailunce AY02 Yagi) for the duration you choose and lists the active emitters with level, SNR and activity %. Presets: full Yagi range, PMR 446, 70 cm, ISM 433.
2. **Track**: pick an emitter from the list (or type a frequency). Live power as a number, a graph and an **audio tone** (higher pitch = stronger signal).
3. **Record point** (button or **volume key**): stores GPS position, time (GPS time), frequency, power, SNR, gain and bearing. The bearing comes from the phone compass, with magnetic declination computed automatically from the GPS position.
4. With 2 or more points it computes the emitter position (intersection / least squares) and draws it on the map together with the rays.

## Build and install
1. Recent Android Studio (Ladybug or newer). **File → Open** and pick this folder; wait for the Gradle *Sync* (it downloads dependencies, so internet is needed once).
2. If Studio offers to upgrade AGP/Kotlin/Compose you can accept; the pinned versions are a known compatible set (AGP 8.7.3, Gradle 8.9, Kotlin 2.0.21).
3. Connect the phone with USB debugging and press **Run**, or **Build → Build APK(s)**.

## First use
1. Open **SDR driver**, plug in the RTL-SDR (accept the USB permission) and tap **Start** (host `127.0.0.1`, port `1234`).
2. Open this app and grant the location permission. In **Track** tap **Connect SDR** (there is also a button to open SDR driver).
3. Go to **Scan** → **Start scan**.

Tips: enable **airplane mode** in the field (GPS keeps working and the phone radios will not interfere with the RTL-SDR), and keep the screen on: the app holds it awake because capture stops if the screen turns off.

## Mounting the phone on the Yagi
- Place the phone **flat** on the boom with its top edge pointing forward (the default). If you mount it differently, choose the axis in **Settings → Phone mounting**.
- A parametric printable mount is in [`hardware/`](hardware/README.md). Print it in **PETG/PLA with no metal or magnets** (steel screws, magnetic mounts…): they disturb the compass. Keep it as far from the dongle and the boom as you can.
- Calibrate the compass by moving the phone in a figure 8 with the mount in place (the Track tab shows the calibration level).
- **Bearing offset**: point the Yagi at something with a known bearing and enter the difference in Settings (it compensates for the phone not being perfectly aligned).
- **Volume keys** record the point without touching the screen; the app averages the bearing over the 2.5 s before the key press (pressing moves the antenna).

## Offline map
Frame your work area on the map and, in **Settings → Offline map**, tap **Download the visible map area** (zoom 12–16 by default, capped at 2000 tiles by the OpenStreetMap usage policy). Then enable **Offline-only map**. Tiles are stored on the phone.

## Useful settings
- **Settle time (ms)**: time discarded after retuning, needed because `rtl_tcp` streams continuously. At 80 ms a 70 MHz sweep takes about 8 s. If ghost emitters from the previous hop appear, raise it; for faster sweeps, lower it.
- **ppm**: RTL-SDR frequency correction (cheap dongles drift by tens of kHz).
- Empty gain or `auto` = tuner AGC. When tracking, start around 30 dB and lower it if "Signal clipping" shows up. Powers are relative dBFS (uncalibrated): good for comparing points taken with the same gain.

## Verification status
- The core logic (`rtl_tcp` client, DSP, scanner, tracker, geometry) compiles with Kotlin 2.0.21 and passes 11 JVM tests against a fake `rtl_tcp` server (`app/src/test`): it detects carriers to within ±3 kHz, measures SNR and locates the emitter to within metres on synthetic data.
- **The Android part (Compose UI, sensors, GPS, osmdroid, database) has not been compiled or tested yet** (it was generated in an environment with no Android SDK or Maven access). If Gradle reports compile errors or the app misbehaves on a phone, those are the first things to fix.
- It has not been tested with a real RTL-SDR: the settle time and the behaviour of SDR driver may need tuning.

## Structure
| File | Purpose |
|---|---|
| `RtlTcp.kt`, `Sdr.kt` | rtl_tcp protocol, configuration, tuning and capture without stale data |
| `Dsp.kt` | FFT, averaged spectrum, median, percentiles |
| `Scanner.kt` | hop-based sweep, maxima, activity %, grouping into emitters |
| `Tracker.kt` | live power and SNR; point power = 90th percentile over 3 s |
| `Geo.kt` | intersection / least squares of bearings |
| `Sensors.kt` | GPS (LocationManager), compass (rotation vector), declination |
| `Db.kt`, `Export.kt` | SQLite, shareable CSV and GeoJSON |
| `MainViewModel.kt`, `Ui.kt`, `MapOverlays.kt`, `MainActivity.kt` | state, Compose UI, map, volume keys |

JVM tests: `./gradlew test` (or *Run tests* in Android Studio).
