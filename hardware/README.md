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
