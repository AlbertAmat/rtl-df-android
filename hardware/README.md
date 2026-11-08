# Yagi phone mount (3D printed)

`yagi_phone_mount.scad` is a parametric OpenSCAD model. Install OpenSCAD (free), open the file, edit the values marked **[MEASURE]** (boom size, phone size with its case), press F6 and export each part as STL (`part = "clamp_top" | "clamp_bottom" | "cradle"`, or `"print"` for all three laid out).

**Parts**
1. **clamp_top**: upper clamp half with a 70 mm post. Print with the flat clamp face on the bed.
2. **clamp_bottom**: lower clamp half, flat side down.
3. **cradle**: phone tray, flat side down. Rear edge open for the USB-C/OTG cable; side tabs only at the corners so the **volume keys stay free**; strap slots near both ends for a velcro strap.

**Hardware (nylon only, no steel near the compass)**
- 4× M4×35 nylon bolts + 4 nylon nuts (clamp)
- 2× M4×12 nylon bolts + 2 nylon nuts (cradle; nuts drop into pockets on top of the post)
- 1–2 velcro straps ~15 mm wide; a thin rubber/foam shim around the boom for grip

**Orientation**: the phone lies flat, screen up, top edge toward the antenna front. In the app keep Settings → Phone mounting = "Top edge of the phone". The tongue/recess keeps the cradle parallel to the boom; calibrate any remaining error with the bearing offset setting.

**Material**: PETG (or ASA outdoors), 4+ perimeters, 30–40% infill. Keep magnets and steel away from the phone; aluminium booms barely affect the compass.

The default boom size (16 mm round) is a placeholder: measure your Yagi boom with calipers before printing.

---

# Pistolete (MP5 grip) for the Yagi AY02

An alternative to the flat cradle above: an MP5 pistol grip with a trigger, clamped on the end of the boom, with the phone standing upright behind it. The trigger presses a switch wired to the phone's 3.5 mm jack, which the phone sees as a headset **volume-up** button; the app's *Volume keys = record point* option turns that into **RECORD FIX**.

## Build the parts

The MP5 lower is licensed CC BY-NC-ND (no derivatives), so the modified STL is **not** in this repository: the script builds your own copy from yours.

1. Download and put in `hardware/vendor/`: `mp5lower_stock_controls.stl` ([thing:4621707](https://www.thingiverse.com/thing:4621707), by kihope) and `redmi_note_8T_full_cover.stl` ([thing:4080906](https://www.thingiverse.com/thing:4080906), by Cthu1hu, CC BY).
2. `python3 -m venv .venv && .venv/bin/pip install numpy trimesh manifold3d shapely mapbox-earcut`
3. `.venv/bin/python build_pistolete.py` (about 30 s). Parts appear in `hardware/out/`; the script also prints the checks (trigger swing, case on the tower, boom in both positions).

| Part | What it is | Size |
|---|---|---|
| `pistolete.stl` | MP5 lower + boom clamp + trigger mechanism + phone tower, **one piece** | 164 × 30 × 217 mm |
| `gatillo.stl` | the trigger | 16 × 10 × 49 mm |
| `funda_redmi_note8t.stl` | Redmi Note 8T case: open at the top, USB-C opening at the bottom, dovetail rail on the back | 80 × 17 × 165 mm |

Print the pistolete on its side, PETG, 4+ perimeters, 40% infill, supports only under the boom tunnel roof if your slicer asks. The case can be PETG too: the phone slides in from the top, nothing needs to flex.

## Hardware (nylon where it is near the compass)

- 1× M3×20 nylon screw + nut (holds the boom; the nut drops into its pocket through the window on top, before sliding the boom in)
- 1× 3 mm pin ≥ 30 mm (e.g. M3×30 nylon screw + nut): trigger pivot
- 1× M3×16 screw: threads into the upper arm of the trigger and presses the switch; screwing it in or out sets the point where the switch fires
- 1× 6×6 mm tactile switch (pocket 6.8 × 6.8 mm, 4.5 mm deep)
- 1× compression spring, about Ø 6 mm and 12–18 mm free length, between the platform behind the trigger and the seat in the trigger (it pushes the trigger forward)

## Wiring to the jack (standard headset-button scheme, not yet tested on the phone)

TRRS plug, CTIA wiring: tip = left, ring 1 = right, ring 2 = **ground**, sleeve = **mic**.
- 2.2 kΩ between mic and ground, always connected, so the phone detects a headset with a microphone.
- Switch in series with a **240 Ω** resistor, between mic and ground: the phone reads it as **volume up**. (0 Ω would read as play/pause; 470 Ω as volume down.)

Leads leave the switch through the Ø 3.5 mm hole at the back of its pocket and the Ø 5 mm hole in the left plate. The case is open at the top, so the plug enters the phone's jack from above.

## Mounting

1. Slide the pistolete onto the **rear end** of the boom and put the M3 screw through the slot you choose (either of the two), from the top window.
2. Slide the case (with the phone) **down** the dovetail on the tower.
3. In the app: Settings → Phone mounting → **Back face (−Z) — phone upright, screen facing you**, and keep **Volume keys = record point** on.

## Assumptions to check

From the sketch: boom 15 × 15 mm, two slots 4 × 11 mm, 31 mm between them, the first 20 mm from the end. Only the 15 × 15 section matters for the geometry (the pistolete can sit on either slot); change `boom`, `boom_clear` and the `[MEASURE]` values at the top of `build_pistolete.py` if your boom differs. Not printed or tried on real hardware: trigger feel, spring and the clearance of the dovetail may need a tweak (`dt_clear`).
