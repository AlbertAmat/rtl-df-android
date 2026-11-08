#!/usr/bin/env python3
"""
Generates the Yagi "pistolete" parts for rtl-df-android:

  out/pistolete.stl   MP5 lower + boom clamp + trigger mechanism + phone-case dovetail tower (ONE piece)
  out/gatillo.stl     the trigger (separate part, pivots on a 3 mm pin)
  out/funda_redmi_note8t.stl   Redmi Note 8T case: open at the top (phone slides in from above),
                               USB-C opening at the bottom, dovetail rail on the back

Inputs (NOT in the repository, put them in hardware/vendor/):
  mp5lower_stock_controls.stl      from https://www.thingiverse.com/thing:4621707  (CC BY-NC-ND)
  redmi_note_8T_full_cover.stl     from https://www.thingiverse.com/thing:4080906  (CC BY)

The MP5 lower is licensed "No Derivatives": this script builds YOUR OWN copy for personal use.
Do not redistribute out/pistolete.stl.

    python3 -m venv .venv && .venv/bin/pip install numpy trimesh manifold3d shapely mapbox-earcut
    .venv/bin/python build_pistolete.py            # builds everything + prints the checks
    .venv/bin/python build_pistolete.py --assembly # also writes out/assembly_preview.stl

Coordinates (gun frame): +X = REAR (towards the shooter), -X = FRONT (towards the antenna's front),
Y = sideways, Z = up. The boom runs along X, centred on Y=0.
"""
import argparse
import os
import sys

import numpy as np
import trimesh
from shapely.geometry import Point
from shapely.ops import unary_union
from trimesh.creation import extrude_polygon

HERE = os.path.dirname(os.path.abspath(__file__))
VENDOR = os.path.join(HERE, "vendor")
OUT = os.path.join(HERE, "out")

# ====================================================================== parameters (mm)
# --- Yagi boom (AY02 sketch): square 15 x 15, two slots on the top face
boom = 15.0
boom_clear = 0.25          # clearance per side in the tunnel
slot_w, slot_l = 4.0, 11.0  # slot size (the screw is M3 so it fits a 4 mm slot)
slot_pitch = 31.0 + slot_l  # centre to centre = 31 mm gap + one slot length   [MEASURE]
end_to_slot = 20.0          # boom end to the first slot's edge               [MEASURE]

# --- boom clamp block (on top of the MP5 lower)
xs = -2.0                  # X of the M3 screw
blk_front, blk_rear = 45.0, 25.0   # block extends xs-45 .. xs+25
blk_z0 = 3.0               # underside of the block (above the trigger arm)
boom_z0 = 7.8              # floor of the boom tunnel
cap_t = 2.9                # roof thickness over the boom
nut_af, nut_h = 5.6, 2.8   # M3 nut pocket (nylon nut)
screw_d = 3.5              # clearance for the M3 screw

# --- trigger
P = (-15.0, -11.0)         # pivot (x, z), pin along Y
A = (-14.5, -4.0)          # upper arm tip (the M3 adjusting screw goes through it)
B = (-20.0, -43.0)         # finger pad centre
pin_d = 3.5                # bore for the 3 mm pin
trig_hw = 5.0              # trigger half width (Y)
travel_deg = 14.0          # pull angle checked for collisions (~8 mm at the finger)

# --- phone tower (female dovetail)
tower_x0, tower_t = 72.0, 12.0     # tower X from 72 to 84 (clears the end of the boom)
tower_hw = 15.0
shelf_z0, shelf_z1 = 26.2, 31.5    # shelf over the boom end
phone_bottom_z = 12.0              # Z of the bottom edge of the phone (USB-C end)
dt_neck, dt_depth, dt_ang = 14.0, 5.0, 30.0   # dovetail: neck width, depth, flank angle
dt_clear = 0.35                    # clearance per side in the groove
rail_case_z = (-80.0, -30.0)       # rail range along the case (Zc): 50 mm, clear of the fingerprint hole (-109..-123)

# ====================================================================== helpers
M = "manifold"


def box(x0, x1, y0, y1, z0, z1):
    b = trimesh.creation.box(extents=[x1 - x0, y1 - y0, z1 - z0])
    b.apply_translation([(x0 + x1) / 2, (y0 + y1) / 2, (z0 + z1) / 2])
    return b


def cyl(axis, c1, c2, r, h0, h1, sections=48):
    """Cylinder along `axis` ('x','y','z'); (c1, c2) = the two other coordinates, from h0 to h1."""
    c = trimesh.creation.cylinder(radius=r, height=h1 - h0, sections=sections)
    c.apply_translation([0, 0, (h0 + h1) / 2])
    if axis == "z":
        c.apply_translation([c1, c2, 0])
    elif axis == "y":      # c1 = x, c2 = z
        c.apply_transform(trimesh.transformations.rotation_matrix(np.pi / 2, [1, 0, 0]))
        c.apply_translation([c1, 0, c2])
    else:                  # axis == 'x': c1 = y, c2 = z
        c.apply_transform(trimesh.transformations.rotation_matrix(np.pi / 2, [0, 1, 0]))
        c.apply_translation([0, c1, c2])
    return c


def union(*ms):
    ms = [m for m in ms if m is not None]
    return trimesh.boolean.union(ms, engine=M)


def diff(a, *bs):
    for b in bs:
        a = trimesh.boolean.difference([a, b], engine=M)
    return a


def inter(a, b):
    return trimesh.boolean.intersection([a, b], engine=M)


def vol(m):
    return float(abs(m.volume)) if len(m.faces) else 0.0


def prism_xz(poly, y0, y1):
    """Extrude a shapely polygon given in (x, z) along Y from y0 to y1."""
    m = extrude_polygon(poly, height=y1 - y0)          # local (x, y=z, z=extrusion)
    m.apply_transform(trimesh.transformations.rotation_matrix(np.pi / 2, [1, 0, 0]))  # -> (x, -ext, z)
    m.apply_translation([0, y1, 0])
    return m


def hexprism_z(cx, cy, af, z0, z1):
    r = af / np.sqrt(3)                                  # circumradius
    pts = [(cx + r * np.cos(np.pi / 3 * i), cy + r * np.sin(np.pi / 3 * i)) for i in range(6)]
    from shapely.geometry import Polygon
    m = extrude_polygon(Polygon(pts), height=z1 - z0)
    m.apply_translation([0, 0, z0])
    return m


def load(name):
    p = os.path.join(VENDOR, name)
    if not os.path.exists(p):
        sys.exit(f"Missing {p}\nDownload it from Thingiverse (see the header of this script) and put it there.")
    m = trimesh.load(p)
    if not m.is_watertight:
        trimesh.repair.fill_holes(m)
    return m


# ====================================================================== trigger
def trigger_2d():
    pc = lambda c, r: Point(c).buffer(r, 48)
    bar = unary_union([pc(P, 5.2), pc(B, 6.0)]).convex_hull
    arm = unary_union([pc(P, 5.2), pc(A, 4.2)]).convex_hull
    return unary_union([bar, arm]).difference(pc(P, pin_d / 2))


def make_trigger():
    t = prism_xz(trigger_2d(), -trig_hw, trig_hw)
    # seat for the spring on the rear face, at the height of the lower blade
    t = diff(t, cyl("x", 0, -17.0, 3.2, P[0] - 1.0, P[0] + 7.0))   # rear seat (blind: stops at the pivot)
    # M3 hole for the adjusting screw (tip presses the switch), through the upper arm along X
    t = diff(t, cyl("x", 0, A[1], 1.35, A[0] - 8, A[0] + 8))
    return t


def rotated_trigger(t, deg):
    m = t.copy()
    # pulling = the finger pad moves towards +X (the shooter); about +Y that is a negative angle
    m.apply_transform(trimesh.transformations.rotation_matrix(-np.radians(deg), [0, 1, 0],
                                                              point=[P[0], 0, P[1]]))
    return m


# ====================================================================== gun
def case_to_gun():
    """Transform from the case's own frame to the gun frame (phone upright, screen towards the shooter)."""
    z_off = phone_bottom_z + 4.42            # case bottom (Zc = +4.42) lands at phone_bottom_z
    T = np.eye(4)
    T[:3, :3] = [[0, -1, 0], [-1, 0, 0], [0, 0, -1]]   # Xc -> -Y, Yc -> -X, Zc -> -Z  (proper rotation)
    T[:3, 3] = [tower_x0 + tower_t + 2.25, 135.5, z_off]
    return T


def make_gun():
    g = load("mp5lower_stock_controls.stl")
    x0, x1 = xs - blk_front, xs + blk_rear
    zt = boom_z0 + boom + 0.5            # roof underside
    ztop = zt + cap_t

    # ---- additions
    blk = box(x0, x1, -12.1, 12.1, blk_z0, ztop)
    shelf = box(x1 - 6, tower_x0 + tower_t, -tower_hw, tower_hw, shelf_z0 - 0.0, shelf_z1)
    # groove geometry (needed to size the tower)
    off = case_to_gun()[2, 3]
    z_rail_lo = -rail_case_z[1] + off     # lower end of the rail (gun Z)
    z_rail_hi = -rail_case_z[0] + off
    tower_top = z_rail_hi + 6.0
    tower = box(tower_x0, tower_x0 + tower_t, -tower_hw, tower_hw, shelf_z0, tower_top)
    # triangular gusset under the shelf / in front of the tower
    from shapely.geometry import Polygon
    gus = prism_xz(Polygon([(tower_x0, shelf_z1 - 0.5), (tower_x0, shelf_z1 + 40), (tower_x0 - 28, shelf_z1 - 0.5)]),
                   -tower_hw + 3, tower_hw - 3)
    # spring / platform behind the trigger (spring pushes the trigger forward)
    plat = box(-4.0, 5.0, -12.1, 12.1, -20.4, blk_z0 + 0.5)
    # switch holder in front of the upper arm
    xf = A[0] - 4.2 - 5.5                   # face the adjusting screw points at
    swb = box(xf - 8.0, xf, -12.1, 12.1, -10.0, blk_z0 + 0.5)
    # pivot bosses on the inside of both plates
    boss = [cyl("y", P[0], P[1], 5.0, s * 8.0, s * 11.9) for s in (-1, 1)]
    g = union(g, blk, shelf, tower, gus, plat, swb, *boss)

    # ---- cuts
    cuts = []
    ty = boom / 2 + boom_clear
    cuts.append(box(x0 - 1, x1 + 1, -ty, ty, boom_z0 - boom_clear, zt))                       # boom tunnel
    cuts.append(box(x1 - 2, tower_x0 + 4, -9.5, 9.5, boom_z0 - boom_clear, zt))               # free path for the boom end
    # (also trims the little stock-hinge stubs of the original lower that sit in that path)
    cuts.append(box(xs - 7, xs + 7, -4.5, 4.5, zt - 0.1, ztop + 1))                           # screw window
    cuts.append(hexprism_z(xs, 0, nut_af, boom_z0 - nut_h, boom_z0 + 0.05))                   # nut pocket
    cuts.append(cyl("z", xs, 0, screw_d / 2, blk_z0 - 1, boom_z0 + 1))                        # screw hole
    # trigger slot in the floor: front edge = rest stop of the blade, rear edge = clear of the pull
    cuts.append(box(-23.9, -7.9, -(trig_hw + 0.7), trig_hw + 0.7, -24.0, -19.0))
    cuts.append(cyl("y", P[0], P[1], pin_d / 2 - 0.1, -15, 15))                               # pivot pin hole
    cuts.append(cyl("x", 0, -17.0, 3.4, -4.1, 2.5))                                           # spring bore
    cuts.append(box(xf - 5.0, xf + 0.1, -3.4, 3.4, A[1] - 3.4, A[1] + 3.4))                   # 6x6 switch pocket
    cuts.append(cyl("x", 0, A[1], 1.8, xf - 9, xf - 4.5))                                     # switch leads
    cuts.append(cyl("y", xf - 9.0, A[1] - 6.0, 2.6, -15.5, -11))                              # wire exit in the plate
    # female dovetail groove in the REAR face of the tower, closed at the bottom, open at the top
    tx1 = tower_x0 + tower_t
    ang = np.tan(np.radians(dt_ang))
    hw0 = dt_neck / 2 + dt_clear
    hw1 = hw0 + dt_depth * ang
    gp = Polygon([(tx1 + 0.5, -hw0 - 0.5 * ang), (tx1 + 0.5, hw0 + 0.5 * ang),
                  (tx1 - dt_depth, hw1), (tx1 - dt_depth, -hw1)])
    gr = extrude_polygon(gp, height=tower_top + 1 - (z_rail_lo - 0.3))
    gr.apply_translation([0, 0, z_rail_lo - 0.3])
    cuts.append(gr)
    g = diff(g, *cuts)
    info = dict(x0=x0, x1=x1, zt=zt, ztop=ztop, tower_top=tower_top, z_rail_lo=z_rail_lo,
                z_rail_hi=z_rail_hi, xf=xf)
    return g, info


# ====================================================================== case
def make_case():
    c = load("redmi_note_8T_full_cover.stl")
    cx = 135.5
    # open the TOP end of the phone (Zc = -161.4 end): remove the end wall + corner arcs, keep the back plate
    c = diff(c, box(90, 181, -12, 0.25, -163, -150.5))
    # BOTTOM end (Zc = +4.4): remove the front lip so the phone can slide in straight, and open the USB-C port
    c = diff(c, box(90, 181, -12, -4.55, -1.0, 6))
    c = diff(c, box(cx - 14, cx + 14, -12, 4, -3.0, 6))
    # male dovetail rail on the back (+Y), along Zc
    ang = np.tan(np.radians(dt_ang))
    hw0 = dt_neck / 2
    hw1 = hw0 + dt_depth * ang
    from shapely.geometry import Polygon
    prof = Polygon([(cx - hw0, 2.0), (cx + hw0, 2.0), (cx + hw1, 2.25 + dt_depth), (cx - hw1, 2.25 + dt_depth)])
    z0, z1 = rail_case_z
    rail = extrude_polygon(prof, height=z1 - z0)
    rail.apply_translation([0, 0, z0])
    # bevel the lower (leading) end so it finds the groove
    bev = box(cx - 20, cx + 20, 2.25 + dt_depth - 1.2, 2.25 + dt_depth + 1, z1 - 1.2, z1 + 1)
    bev.apply_transform(trimesh.transformations.rotation_matrix(np.radians(45), [1, 0, 0],
                                                                point=[cx, 2.25 + dt_depth, z1]))
    rail = diff(rail, bev)
    return union(c, rail)


# ====================================================================== main
def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--assembly", action="store_true", help="also write out/assembly_preview.stl")
    args = ap.parse_args()
    os.makedirs(OUT, exist_ok=True)

    print("trigger ...")
    trig = make_trigger()
    print("gun (the MP5 mesh has ~800k triangles, this takes a minute) ...")
    gun, info = make_gun()
    print("case ...")
    case = make_case()

    print("\n=== checks ===")
    for name, m in (("pistolete", gun), ("gatillo", trig), ("funda", case)):
        print(f"{name:10s} watertight={m.is_watertight}  size={np.round(m.extents, 1).tolist()}  volume={vol(m)/1000:.1f} cm3")
    worst = 0.0
    for deg in np.linspace(0, travel_deg, 8):
        v = vol(inter(gun, rotated_trigger(trig, deg)))
        worst = max(worst, v)
        print(f"  trigger at {deg:4.1f} deg: overlap with the body = {v:.2f} mm3")
    print("TRIGGER SWING:", "OK, free over the whole travel" if worst < 1.0 else "COLLISION - adjust P/B/slot")
    T = case_to_gun()
    case_g = case.copy()
    case_g.apply_transform(T)
    ov = vol(inter(gun, case_g))
    print(f"case on the tower: overlap = {ov:.2f} mm3 ->", "OK, slides in with clearance" if ov < 1.0 else "INTERFERENCE")
    # boom in its two positions: screw in slot 1 (end 25.5 mm behind the screw) or slot 2 (67.5 mm behind)
    for n, behind in (("slot 1", end_to_slot + slot_l / 2), ("slot 2", end_to_slot + slot_l / 2 + slot_pitch)):
        end_x = xs + behind
        boom_m = box(end_x - 200, end_x, -boom / 2, boom / 2, boom_z0, boom_z0 + boom)
        print(f"boom with the screw in {n}: end at x={end_x:.1f} (tower at {tower_x0}), "
              f"overlap = {vol(inter(gun, boom_m)):.2f} mm3 (must be 0)")

    gun.export(os.path.join(OUT, "pistolete.stl"))
    trig.export(os.path.join(OUT, "gatillo.stl"))
    case.export(os.path.join(OUT, "funda_redmi_note8t.stl"))
    if args.assembly:
        asm = trimesh.util.concatenate([gun, rotated_trigger(trig, 0), case_g])
        asm.export(os.path.join(OUT, "assembly_preview.stl"))
    print("\nwritten to", OUT)


if __name__ == "__main__":
    main()
