# How to use RDF Finder

A field guide: from plugging in the dongle to a position on the map.

## What you need
- An Android phone (8.0+) with GPS and a compass, and a USB OTG adapter.
- An RTL-SDR dongle and a directional antenna (the app is tuned for a 400–470 MHz Yagi).
- The **SDR driver** app (it serves the dongle as `rtl_tcp` on `127.0.0.1:1234`).
- Install the APK from the [Releases page](https://github.com/AlbertAmat/rtl-df-android/releases) (allow "install unknown apps" when asked).

## 1. Set up (once per session)
1. Plug the RTL-SDR into the phone. Open **SDR driver**, accept the USB permission and tap **Start** (host `127.0.0.1`, port `1234`).
2. Open **RDF Finder** and grant the location (and notification) permission.
3. Go to the **TRACK** tab and tap **CONNECT SDR**. The header chips show `GPS`, `SDR LINK` and `TRACKING` status; wait for GPS to show a fix with a small error (±5–10 m is good).
4. In the field, switch on airplane mode (GPS keeps working) and leave the screen on.

Mount the phone flat on the antenna boom, top edge pointing forward, and calibrate the compass by moving the phone in a figure 8. See *Mounting the phone* in the [README](../README.md).

## 2. Find the signal: SCAN tab
1. Pick a preset (Yagi range, PMR 446, 70 cm, ISM 433) or type the **From / To** frequencies in MHz.
2. Set the **duration** (seconds) and the **SNR threshold**. Longer scans catch intermittent transmitters.
3. Tap **START SCAN**. Emitters appear with level (dB), SNR and **activity %** (how often they were on air).
4. Tap **TRACK** on the row you want. It copies the frequency to the Track tab.

## 3. Follow it: TRACK tab
1. Check **Frequency (MHz)**, **Channel (kHz)** (12.5 for narrow FM) and **Gain (dB)** (start near 30), then tap **START TRACK**.
2. Watch the big number (power in dBFS), the SNR and the graph. The **TONE** button adds a beeper: higher pitch means a stronger signal.
3. Turn the antenna: the signal is strongest when the Yagi points at the transmitter.

### When you get close: attenuate
A very strong signal overloads the receiver and the reading becomes wrong. When you see the red **⚠ CLIPPING**:
- tap **− ATT** to lower the gain (**+ GAIN** raises it), or
- switch on **AUTO ATT** and the app lowers the gain by itself.

Points recorded afterwards store the gain that was active when they were measured.

### Listen to the channel
In the **RECEIVER** panel tap **▶ LISTEN** to hear the frequency on the phone speaker. Choose **NFM** (walkie-talkies, amateur) or **AM** (airband). Raise the **squelch** (SNR dB) if you hear hiss; `● RX` means the squelch is open. Tap **■ STOP LISTENING** to stop. Check your local rules on monitoring radio communications.

### Get the bearing
Two ways:
- **Sweep assist (recommended):** swing the antenna slowly through a full turn. The app builds a polar plot of power versus heading and shows `PEAK … ° true`. When the lobe is clear, **RECORD FIX AT PEAK** enables.
- **Manual:** point the antenna at the strongest signal and press **RECORD FIX · VOLUME KEY** (or a volume key; the bearing is averaged over the 2.5 s before the press). You can also type a bearing in **ANTENNA BEARING** if you are not using the compass.

Each fix stores position, time, frequency, power, SNR, gain and bearing.

## 4. Locate it
1. Record at least **two** fixes from different places, ideally **three or more**, spread around the emitter (angles far apart, e.g. 90°). Walking 200–500 m between fixes is typical.
2. The **ESTIMATED EMITTER** panel shows the coordinates, residual, and a **95% confidence ellipse**. The map draws the rays (amber), your position (blue) and the emitter (red).
3. With 4+ fixes, a clearly inconsistent bearing (reflection, wrong signal) is dropped and shown dashed grey.
4. **CENTER ON MAP** zooms to the result; **OPEN IN MAPS** hands the coordinates to your navigation app.
5. Walk toward the estimate, track again and record new fixes to refine it. Close in, keep an eye on clipping.

Bad GPS accuracy (over 25 m) shows a warning. A small ellipse and low residual mean the fixes agree.

## 5. Save and share: SESSIONS tab
Each hunt is a session. Open one to review its fixes, or tap **NEW HUNT (TRACK FREQ.)** to start a fresh one. **SHARE CSV** and **SHARE GEOJSON** export the fixes for spreadsheets, QGIS or other tools.

## 6. Settings worth knowing
| Setting | What it does |
|---|---|
| Map style | Topographic (default) or Street |
| Night mode | Red-on-black display that keeps night vision |
| Use the phone compass | Off = type bearings by hand |
| Bearing offset | Compensates for the phone not being aligned with the antenna |
| Phone mounting | Which phone axis points along the boom |
| Volume keys = record point | Record a fix without touching the screen |
| ppm | Frequency correction for cheap dongles |
| Settle time (ms) | Raise it if ghost emitters from the previous hop appear |
| Offline map | Frame the area on the map, **DOWNLOAD VISIBLE AREA**, then enable offline-only |

## Troubleshooting
| Symptom | Try |
|---|---|
| CONNECT SDR fails | Is SDR driver running with the dongle started on port 1234? Tap **OPEN SDR DRIVER** and start it first. |
| Connection drops | There is no auto reconnect: tap **CONNECT SDR** again. |
| No emitters in the scan | Lower the SNR threshold, lengthen the duration, check the antenna, try a gain around 24–30 dB. |
| Reading flat at the top, CLIPPING | Lower the gain (− ATT or AUTO ATT), or move away. |
| Bearings jump around | Calibrate the compass (figure 8), keep metal and magnets away from the phone, use sweep assist. |
| Estimate far off | Fixes too close together or at similar angles; take more, from different directions. Check GPS accuracy. |
| Sound is only hiss | Raise the squelch SNR, or switch NFM/AM. |
| Capture stops when the screen turns off | Keep the screen on (the app holds it awake) and leave the capture notification in place. |
