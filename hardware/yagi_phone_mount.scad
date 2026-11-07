// Phone mount for a Yagi antenna boom (rtl-df-android)
// Open in OpenSCAD (free): F5 = preview, F6 = render, File > Export > STL.
// Set `part` to export each piece, or "print" for a ready-to-slice layout.
//
// Coordinates (assembled): X = along the boom, +X = antenna FRONT (where it points);
// Y = sideways; Z = up. The phone lies flat, screen up, top edge toward +X
// (= "Top edge of the phone (+Y)" in the app's Settings -> Phone mounting).
//
// MEASURE and edit the values marked [MEASURE] before printing.

part = "preview";   // "preview", "print", "clamp_top", "clamp_bottom", "cradle"

/* [Boom - MEASURE] */
boom_w     = 16;    // boom width in mm (diameter if round)   [MEASURE: placeholder]
boom_h     = 16;    // boom height in mm (same as width if round)
boom_round = true;  // false for a square/rectangular boom
boom_clear = 0.3;   // extra clearance; use a thin rubber/foam shim for grip

/* [Phone - MEASURE, with its case] */
phone_l = 160;      // length, mm
phone_w = 75;       // width, mm
phone_t = 9;        // thickness, mm (only used for the preview ghost)

/* [Mount] */
riser_h = 70;       // post height above the clamp (keeps the phone clear of the boom)
wall    = 6;        // wall thickness around the boom
clamp_w = 34;       // clamp length along the boom
post_w  = 18;       // post width (Y)
gap     = 1;        // gap between clamp halves so the bolts really clamp

/* [Hardware: NYLON M4, no steel near the compass] */
bolt_d = 4.5;       // M4 clearance hole
head_d = 8.2;       // nylon M4 head diameter
head_h = 3.5;       // head height
nut_af = 7;         // M4 nut across flats
nut_h  = 3.6;       // M4 nut thickness

/* [Cradle] */
tol     = 1;        // phone clearance per side
plate_t = 8;        // cradle base thickness
lip_t   = 3;        // lip thickness
lip_h   = 6;        // lip height
tab_l   = 22;       // side corner tab length (middle left open for the volume keys)
strap_w = 16;       // velcro strap width
strap_t = 3.5;      // slot thickness for the strap

$fn = 72;

// ---- derived ----
bolt_y   = boom_w/2 + 7.5;
bolt_x   = clamp_w/2 - 8;
blk_w    = 2*(bolt_y + 6);
blk_h    = boom_h/2 + wall;
top_z    = gap/2 + blk_h;
post_top = top_z + riser_h;
tongue_h = 1.6;
cr_bolt_x = 8;

module cav() {
    if (boom_round)
        rotate([0,90,0]) cylinder(d = boom_w + boom_clear, h = clamp_w + 2, center = true);
    else
        cube([clamp_w + 2, boom_w + boom_clear, boom_h + boom_clear], center = true);
}

module hexpocket(h) { cylinder(d = nut_af/cos(30) + 0.4, h = h, $fn = 6); }

// Upper clamp half + post. Print with the flat clamp face on the bed.
module clamp_top() {
    difference() {
        union() {
            translate([-clamp_w/2, -blk_w/2, gap/2]) cube([clamp_w, blk_w, blk_h]);
            hull() {   // gusset at the base of the post
                translate([-clamp_w/2, -10.5, top_z - 0.01]) cube([clamp_w, 21, 0.01]);
                translate([-clamp_w/2, -post_w/2, top_z + 15]) cube([clamp_w, post_w, 0.01]);
            }
            translate([-clamp_w/2, -post_w/2, top_z]) cube([clamp_w, post_w, riser_h]);
            // alignment tongue: stops the cradle from yawing
            translate([-(clamp_w-2)/2, -(post_w-2)/2, post_top - 0.01])
                cube([clamp_w - 2, post_w - 2, tongue_h + 0.01]);
        }
        cav();
        for (sx = [-1, 1], sy = [-1, 1]) translate([sx*bolt_x, sy*bolt_y, 0]) {
            translate([0, 0, -1]) cylinder(d = bolt_d, h = top_z + 2);
            translate([0, 0, top_z - head_h - 0.2]) cylinder(d = head_d + 0.6, h = head_h + 1);
        }
        // nut pockets for the cradle bolts (nuts drop in from the top)
        for (sx = [-1, 1]) translate([sx*cr_bolt_x, 0, 0]) {
            translate([0, 0, post_top + tongue_h - nut_h - 0.4]) hexpocket(nut_h + 1);
            translate([0, 0, post_top - 12]) cylinder(d = bolt_d, h = 13);
        }
    }
}

// Lower clamp half. Print as it is (flat bottom on the bed).
module clamp_bottom() {
    z0 = -gap/2 - blk_h;
    difference() {
        translate([-clamp_w/2, -blk_w/2, z0]) cube([clamp_w, blk_w, blk_h]);
        cav();
        for (sx = [-1, 1], sy = [-1, 1]) translate([sx*bolt_x, sy*bolt_y, z0 - 1]) {
            cylinder(d = bolt_d, h = blk_h + gap + 2);
            hexpocket(nut_h + 1);
        }
    }
}

// Phone cradle, printed flat. Rear (-X) edge is open for the USB-C/OTG cable.
module cradle() {
    slot_y  = phone_w/2 + tol + lip_t + 1 + strap_t/2;
    half_w  = slot_y + strap_t/2 + 3;
    x0      = -(phone_l/2 + tol);
    xf      = phone_l/2 + tol;
    x1      = xf + lip_t;
    strap_x = phone_l/2 - 14;     // keeps the straps away from the side buttons
    ix      = phone_w/2 + tol;    // inner Y edge of the pocket
    difference() {
        union() {
            translate([x0, -half_w, 0]) cube([x1 - x0, 2*half_w, plate_t]);
            translate([xf, -(ix + lip_t), plate_t]) cube([lip_t, 2*(ix + lip_t), lip_h]);   // front lip
            for (sy = [-1, 1]) {
                oy = (sy > 0) ? ix : -(ix + lip_t);
                translate([xf - tab_l, oy, plate_t]) cube([tab_l, lip_t, lip_h]);           // front tabs
                translate([x0, oy, plate_t])        cube([tab_l, lip_t, lip_h]);            // rear tabs
            }
        }
        for (sx = [-1, 1], sy = [-1, 1])   // strap slots
            translate([sx*strap_x - strap_w/2, sy*slot_y - strap_t/2, -1])
                cube([strap_w, strap_t, plate_t + 2]);
        translate([-(clamp_w-2)/2 - 0.2, -(post_w-2)/2 - 0.2, -0.01])                   // tongue recess
            cube([clamp_w - 2 + 0.4, post_w - 2 + 0.4, tongue_h + 0.2]);
        for (sx = [-1, 1]) translate([sx*cr_bolt_x, 0, 0]) {                            // bolts
            translate([0, 0, -1]) cylinder(d = bolt_d, h = plate_t + 2);
            translate([0, 0, plate_t - head_h - 0.2]) cylinder(d = head_d + 0.6, h = head_h + 1);
        }
    }
}

if (part == "clamp_top") {
    translate([0, 0, -gap/2]) clamp_top();
} else if (part == "clamp_bottom") {
    translate([0, 0, gap/2 + blk_h]) clamp_bottom();
} else if (part == "cradle") {
    cradle();
} else if (part == "print") {
    translate([0, 0, -gap/2]) clamp_top();
    translate([0, 60, gap/2 + blk_h]) clamp_bottom();
    translate([0, -95, 0]) cradle();
} else {
    // assembled preview: boom, clamp, post, cradle, phone ghost
    color("silver")
        if (boom_round) rotate([0,90,0]) cylinder(d = boom_w, h = 300, center = true);
        else cube([300, boom_w, boom_h], center = true);
    color("orange") clamp_top();
    color("gold") clamp_bottom();
    color("teal") translate([0, 0, post_top]) cradle();
    %translate([-phone_l/2, -phone_w/2, post_top + plate_t]) cube([phone_l, phone_w, phone_t]);
}
