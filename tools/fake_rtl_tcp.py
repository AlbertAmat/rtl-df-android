#!/usr/bin/env python3
"""Fake rtl_tcp for demos: synthetic carriers, real-time paced IQ. Target level read from a file."""
import socket, struct, sys, threading, time
import numpy as np

CARRIERS = [(433.920e6, 0.10), (446.0625e6, 0.18), (446.100e6, 0.25), (462.5625e6, 0.12), (410.150e6, 0.08)]
TARGET = 446.100e6
LEVEL_FILE = sys.argv[2] if len(sys.argv) > 2 else None
rng = np.random.default_rng(1)

def level():
    try:
        return float(open(LEVEL_FILE).read())
    except Exception:
        return 1.0

def serve(c):
    st = {"f": 100e6, "fs": 2.048e6}
    def reader():
        buf = b""
        while True:
            d = c.recv(64)
            if not d: return
            buf += d
            while len(buf) >= 5:
                code, val = struct.unpack(">BI", buf[:5]); buf = buf[5:]
                if code == 1: st["f"] = val
                elif code == 2: st["fs"] = val
    c.sendall(b"RTL0" + struct.pack(">II", 5, 29))
    threading.Thread(target=reader, daemon=True).start()
    n0 = 0; t0 = time.time(); sent = 0; last_fs = st['fs']
    try:
        while True:
            fs = st["fs"]; f = st["f"]; n = 16384
            if fs != last_fs: last_fs = fs; t0 = time.time(); sent = 0
            t = (np.arange(n) + n0) / fs; n0 += n
            x = (rng.standard_normal(n) + 1j * rng.standard_normal(n)) * 0.01
            for cf, a in CARRIERS:
                off = cf - f
                if abs(off) < fs * 0.45:
                    amp = a * (level() if cf == TARGET else 1.0)
                    x += amp * np.exp(2j * np.pi * off * t)
            iq = np.empty(2 * n, np.uint8)
            iq[0::2] = np.clip(x.real * 127 + 127.5, 0, 255); iq[1::2] = np.clip(x.imag * 127 + 127.5, 0, 255)
            c.sendall(iq.tobytes()); sent += n
            lag = sent / fs - (time.time() - t0)
            if lag > 0: time.sleep(lag)
    except OSError:
        pass

s = socket.socket(); s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
s.bind(("0.0.0.0", int(sys.argv[1]) if len(sys.argv) > 1 else 1234)); s.listen(2)
while True:
    c, _ = s.accept(); threading.Thread(target=serve, args=(c,), daemon=True).start()
