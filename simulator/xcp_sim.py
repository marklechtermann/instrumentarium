#!/usr/bin/env python3
"""
XCP-on-Ethernet slave simulator for Instrumentarium.

Runs the same vehicle model as the app's mock platform, keeps its signals in a
simulated ECU memory and serves it via XCP (TCP and UDP, default port 5555).
The matching A2L description is written to demo.a2l and served over HTTP
(default http://<host>:8080/demo.a2l), so the app can load it by URL.

Only the Python standard library is needed:

    python3 xcp_sim.py                 # TCP+UDP 5555, HTTP 8080
    python3 xcp_sim.py --port 5556 --http-port 0 --big-endian

Supported XCP commands: CONNECT, DISCONNECT, GET_STATUS, SYNCH, GET_COMM_MODE_INFO,
SET_MTA, UPLOAD, SHORT_UPLOAD, DOWNLOAD, SHORT_DOWNLOAD.
"""
import argparse
import http.server
import math
import os
import random
import socket
import socketserver
import struct
import threading
import time

# ---------------------------------------------------------------------------
# Variable table: single source of truth for memory layout and A2L generation.
# conv: None = identical, ("LIN", a, b) phys = a*raw + b, ("RAT", factor) raw = factor*phys
# ---------------------------------------------------------------------------
ONOFF = {0: "Off", 1: "On"}
GEAR = {0: "N", 1: "1", 2: "2", 3: "3", 4: "4", 5: "5", 6: "6"}
ENGINE_STATE = {0: "Off", 1: "Idle", 2: "Load", 3: "Overrun", 4: "Limiter"}

MEAS = [
    # name, dtype, conv, unit, min, max, description, vtab
    ("Engine.Speed", "UWORD", ("LIN", 0.25, 0.0), "rpm", 0, 7000, "Engine speed", None),
    ("Engine.Torque", "SWORD", ("LIN", 0.1, 0.0), "Nm", -50, 350, "Engine torque", None),
    ("Engine.Load", "UBYTE", ("LIN", 0.5, 0.0), "%", 0, 100, "Actual throttle position", None),
    ("Engine.CoolantTemp", "UWORD", ("LIN", 0.01, -40.0), "degC", -40, 140, "Coolant temperature", None),
    ("Engine.OilPressure", "FLOAT32_IEEE", None, "bar", 0, 6, "Oil pressure", None),
    ("Engine.State", "UBYTE", None, "-", 0, 4, "Engine state", ("VT_EngineState", ENGINE_STATE)),
    ("Vehicle.Speed", "UWORD", ("RAT", 128.0), "km/h", 0, 250, "Vehicle speed", None),
    ("Vehicle.Gear", "UBYTE", None, "-", 0, 6, "Engaged gear", ("VT_Gear", GEAR)),
    ("Vehicle.AccelLong", "FLOAT32_IEEE", None, "m/s^2", -10, 10, "Longitudinal acceleration", None),
    ("Vehicle.AccelLat", "FLOAT32_IEEE", None, "m/s^2", -10, 10, "Lateral acceleration", None),
    ("Vehicle.YawRate", "FLOAT32_IEEE", None, "deg/s", -60, 60, "Yaw rate", None),
    ("Vehicle.Odometer", "FLOAT32_IEEE", None, "km", 0, 1000, "Distance travelled", None),
    ("Electrical.BatteryVoltage", "UWORD", ("LIN", 0.001, 0.0), "V", 10, 15, "Supply voltage", None),
    ("Electrical.FanActive", "UBYTE", None, "-", 0, 1, "Fan active", ("VT_OnOff", ONOFF)),
    ("Status.OverheatWarning", "UBYTE", None, "-", 0, 1, "Overheat warning", ("VT_OnOff", ONOFF)),
    ("Status.RevLimiter", "UBYTE", None, "-", 0, 1, "Rev limiter active", ("VT_OnOff", ONOFF)),
    ("Signals.Time", "FLOAT64_IEEE", None, "s", 0, 1e6, "Simulation time", None),
    ("Signals.Sine", "FLOAT32_IEEE", None, "-", -10, 10, "Sine", None),
    ("Signals.Square", "FLOAT32_IEEE", None, "-", -10, 10, "Square", None),
    ("Signals.Sawtooth", "FLOAT32_IEEE", None, "-", -10, 10, "Sawtooth", None),
    ("Signals.Noise", "FLOAT32_IEEE", None, "-", -10, 10, "Noise", None),
]

PARAMS = [
    ("Driver.Ignition", "UBYTE", None, "-", 0, 1, "Ignition", ("VT_OnOff", ONOFF), 1),
    ("Driver.AutoDrive", "UBYTE", None, "-", 0, 1, "Automatic drive cycle", ("VT_OnOff", ONOFF), 1),
    ("Driver.ThrottleCmd", "FLOAT32_IEEE", None, "%", 0, 100, "Accelerator pedal", None, 0.0),
    ("Driver.BrakeCmd", "FLOAT32_IEEE", None, "%", 0, 100, "Brake pedal", None, 0.0),
    ("Driver.SteeringAngle", "SWORD", ("LIN", 0.1, 0.0), "deg", -270, 270, "Steering wheel angle", None, 0.0),
    ("Calib.IdleSpeed", "UWORD", None, "rpm", 600, 1200, "Idle speed setpoint", None, 800),
    ("Calib.RevLimit", "UWORD", None, "rpm", 4000, 7000, "Rev limit", None, 6200),
    ("Calib.FanThreshold", "UBYTE", ("LIN", 1.0, -40.0), "degC", 80, 110, "Fan switch-on threshold", None, 98),
    ("Calib.FanEnable", "UBYTE", None, "-", 0, 1, "Fan enable", ("VT_OnOff", ONOFF), 1),
    ("Signals.Frequency", "FLOAT32_IEEE", None, "Hz", 0.05, 5, "Test signal frequency", None, 0.5),
    ("Signals.Amplitude", "FLOAT32_IEEE", None, "-", 0, 10, "Test signal amplitude", None, 5.0),
]

FMT = {"UBYTE": "B", "SBYTE": "b", "UWORD": "H", "SWORD": "h", "ULONG": "I", "SLONG": "i",
       "FLOAT32_IEEE": "f", "FLOAT64_IEEE": "d"}
MEAS_BASE = 0x00010000
PARAM_BASE = 0x00020000
MEM_SIZE = 0x1000


def to_raw(conv, phys):
    if conv is None:
        return phys
    if conv[0] == "LIN":
        return (phys - conv[2]) / conv[1]
    return phys * conv[1]


def to_phys(conv, raw):
    if conv is None:
        return raw
    if conv[0] == "LIN":
        return conv[1] * raw + conv[2]
    return raw / conv[1]


class Memory:
    """Simulated ECU memory with A2L-addressed variables."""

    def __init__(self, big_endian=False):
        self.order = ">" if big_endian else "<"
        self.lock = threading.Lock()
        self.segments = {MEAS_BASE: bytearray(MEM_SIZE), PARAM_BASE: bytearray(MEM_SIZE)}
        self.addr = {}
        for base, table in ((MEAS_BASE, MEAS), (PARAM_BASE, PARAMS)):
            off = 0
            for v in table:
                size = struct.calcsize(FMT[v[1]])
                off = (off + size - 1) // size * size  # natural alignment
                self.addr[v[0]] = (base + off, v)
                off += size
        for p in PARAMS:
            self.set(p[0], p[8])

    def _seg(self, address, n):
        for base, seg in self.segments.items():
            if base <= address and address + n <= base + len(seg):
                return seg, address - base
        raise IndexError(address)

    def read(self, address, n):
        with self.lock:
            seg, off = self._seg(address, n)
            return bytes(seg[off:off + n])

    def write(self, address, data):
        with self.lock:
            seg, off = self._seg(address, len(data))
            seg[off:off + len(data)] = data

    def set(self, name, phys):
        address, v = self.addr[name]
        fmt = FMT[v[1]]
        raw = to_raw(v[2], phys)
        if fmt not in "fd":
            lo, hi = {"B": (0, 255), "b": (-128, 127), "H": (0, 65535), "h": (-32768, 32767),
                      "I": (0, 2**32 - 1), "i": (-2**31, 2**31 - 1)}[fmt]
            raw = max(lo, min(hi, int(round(raw))))
        self.write(address, struct.pack(self.order + fmt, raw))

    def get(self, name):
        address, v = self.addr[name]
        fmt = FMT[v[1]]
        raw = struct.unpack(self.order + fmt, self.read(address, struct.calcsize(fmt)))[0]
        return to_phys(v[2], raw)


# ---------------------------------------------------------------------------
# Vehicle model (port of VehicleModel.kt)
# ---------------------------------------------------------------------------
RATIOS = [0.0, 3.6, 2.1, 1.4, 1.0, 0.8, 0.65]
FINAL, WHEEL_R, MASS, WHEELBASE, STEER_RATIO = 3.9, 0.31, 1400.0, 2.7, 15.0


class VehicleModel:
    def __init__(self, mem):
        self.mem = mem
        self.t = self.v = self.rpm = self.load = self.odo = self.accel = self.torque = 0.0
        self.gear, self.coolant, self.fan, self.cool_down = 0, 75.0, False, 0.0
        self.rnd = random.Random(42)

    def p(self, name):
        return self.mem.get(name)

    def step(self, dt):
        self.t += dt
        t = self.t
        ign = self.p("Driver.Ignition") >= 0.5
        if self.p("Driver.AutoDrive") >= 0.5:
            ph = t % 60.0
            throttle, brake = (60, 0) if ph < 15 else (25, 0) if ph < 30 else (0, 35) if ph < 38 else \
                (0, 0) if ph < 44 else (90, 0) if ph < 54 else (0, 55)
            steer = 15.0 * math.sin(2 * math.pi * t / 20.0)
        else:
            throttle, brake = self.p("Driver.ThrottleCmd"), self.p("Driver.BrakeCmd")
            steer = self.p("Driver.SteeringAngle")
        self.steer = steer
        if not ign:
            throttle = 0.0
        self.load += (throttle - self.load) * min(1.0, dt / 0.15)
        idle, rev_limit = self.p("Calib.IdleSpeed"), self.p("Calib.RevLimit")
        if ign:
            if self.gear == 0:
                self.gear = 1
            rpm_wheel = self.v / WHEEL_R * 60.0 / (2 * math.pi) * RATIOS[self.gear] * FINAL
            self.rpm += (max(idle, rpm_wheel) - self.rpm) * min(1.0, dt / 0.1)
            limiter = self.rpm >= rev_limit
            max_tq = 180.0 + 140.0 * math.exp(-((self.rpm - 3500.0) / 2200.0) ** 2)
            self.torque = self.load / 100.0 * max_tq - (10.0 + self.rpm * 0.004)
            if limiter:
                self.torque = min(self.torque, 0.0)
            wheel_force = 0.0 if (rpm_wheel < idle * 0.9 and self.load < 1.0) else \
                self.torque * RATIOS[self.gear] * FINAL / WHEEL_R * 0.9
        else:
            self.gear = 0
            self.rpm *= math.exp(-dt / 0.4)
            if self.rpm < 1:
                self.rpm = 0.0
            self.torque = 0.0
            wheel_force = 0.0
        drag = 0.5 * 1.2 * 0.65 * 2.2 * self.v ** 2 + (0.012 * MASS * 9.81 if self.v > 0.1 else 0.0)
        brake_force = brake / 100.0 * 12000.0 if self.v > 0 else 0.0
        self.accel = (wheel_force - drag - brake_force) / MASS
        self.v += self.accel * dt
        if self.v <= 0:
            self.v = 0.0
            self.accel = max(self.accel, 0.0)
        self.odo += self.v * dt / 1000.0
        self.cool_down -= dt
        if ign and self.cool_down <= 0:
            if self.rpm > 2500 + self.load * 30 and self.gear < 6:
                self.gear += 1
                self.cool_down = 0.8
            elif self.rpm < 1300 and self.gear > 1:
                self.gear -= 1
                self.cool_down = 0.8
        thr = self.p("Calib.FanThreshold")
        self.fan = self.p("Calib.FanEnable") >= 0.5 and ign and (
            self.coolant > thr or (self.fan and self.coolant > thr - 3.0))
        target = (85.0 + self.load * 0.25 + self.rpm / 7000.0 * 15.0 - (15.0 if self.fan else 0.0)
                  - self.v * 3.6 / 250.0 * 10.0) if ign else 20.0
        self.coolant += (target - self.coolant) * dt / (20.0 if ign else 120.0)
        self.publish(ign)

    def publish(self, ign):
        m = self.mem
        delta = self.steer / STEER_RATIO * math.pi / 180.0
        lat_raw = self.v ** 2 * math.tan(delta) / WHEELBASE
        lat = max(-9.5, min(9.5, lat_raw))
        yaw = (lat / self.v if abs(lat_raw) > 9.5 and self.v > 0.1 else self.v * math.tan(delta) / WHEELBASE) * 180 / math.pi
        f, a = self.p("Signals.Frequency"), self.p("Signals.Amplitude")
        phase = (self.t * f) % 1.0
        state = 0 if not ign else 4 if self.rpm >= self.p("Calib.RevLimit") else \
            2 if self.torque > 5 and self.load > 1 else 3 if self.load < 1 and self.v > 1 else 1
        s = math.sin(2 * math.pi * phase)
        values = {
            "Engine.Speed": self.rpm, "Engine.Torque": self.torque, "Engine.Load": self.load,
            "Engine.CoolantTemp": self.coolant,
            "Engine.OilPressure": max(0.0, min(6.0, 1.0 + self.rpm / 7000.0 * 4.0 - (self.coolant - 90.0) * 0.01))
            if ign and self.rpm > 100 else 0.0,
            "Engine.State": state, "Vehicle.Speed": self.v * 3.6, "Vehicle.Gear": self.gear,
            "Vehicle.AccelLong": self.accel, "Vehicle.AccelLat": lat, "Vehicle.YawRate": max(-60, min(60, yaw)),
            "Vehicle.Odometer": self.odo,
            "Electrical.BatteryVoltage": (14.2 if ign and self.rpm > 500 else 12.5) - (0.3 if self.fan else 0.0)
            + self.rnd.gauss(0, 0.02),
            "Electrical.FanActive": 1 if self.fan else 0,
            "Status.OverheatWarning": 1 if self.coolant > 110.0 else 0,
            "Status.RevLimiter": 1 if state == 4 else 0,
            "Signals.Time": self.t, "Signals.Sine": a * s, "Signals.Square": a * (1.0 if s >= 0 else -1.0),
            "Signals.Sawtooth": a * (2 * phase - 1), "Signals.Noise": self.rnd.gauss(0, 1) * a * 0.2,
        }
        for k, v in values.items():
            m.set(k, v)


# ---------------------------------------------------------------------------
# A2L generation
# ---------------------------------------------------------------------------
def a2l_text(mem, big_endian=False, port=5555):
    out = ['ASAP2_VERSION 1 71', '/begin PROJECT InstrumentariumDemo "Demo-ECU for Instrumentarium"',
           '  /begin MODULE DemoECU "Vehicle model served by xcp_sim.py"',
           '    /begin MOD_COMMON ""',
           f'      BYTE_ORDER {"MSB_FIRST" if big_endian else "MSB_LAST"}',
           '      ALIGNMENT_BYTE 1', '    /end MOD_COMMON',
           f'    /* XCP on Ethernet: TCP/UDP port {port} */']
    vtabs, methods, layouts = {}, {}, set()

    def method(var):
        name, dtype, conv, unit, vtab = var[0], var[1], var[2], var[3], var[7]
        if vtab:
            vtabs[vtab[0]] = vtab[1]
            key = f"CM_{vtab[0]}"
            methods[key] = f'    /begin COMPU_METHOD {key} "" TAB_VERB "%.0f" "{unit}"\n      COMPU_TAB_REF {vtab[0]}\n    /end COMPU_METHOD'
            return key
        if conv is None:
            key = f"CM_IDENT_{unit.replace('/', '_').replace('^', '').replace('%', 'pct').replace('-', 'none')}"
            methods[key] = f'    /begin COMPU_METHOD {key} "" IDENTICAL "%.3f" "{unit}"\n    /end COMPU_METHOD'
            return key
        if conv[0] == "LIN":
            key = "CM_" + name.replace(".", "_")
            methods[key] = (f'    /begin COMPU_METHOD {key} "" LINEAR "%.2f" "{unit}"\n'
                            f'      COEFFS_LINEAR {conv[1]} {conv[2]}\n    /end COMPU_METHOD')
            return key
        key = "CM_" + name.replace(".", "_")
        methods[key] = (f'    /begin COMPU_METHOD {key} "" RAT_FUNC "%.2f" "{unit}"\n'
                        f'      COEFFS 0 {conv[1]} 0 0 0 1\n    /end COMPU_METHOD')
        return key

    for v in MEAS:
        addr = mem.addr[v[0]][0]
        out.append(f'    /begin MEASUREMENT {v[0]} "{v[6]}" {v[1]} {method(v)} 0 0 {v[4]} {v[5]}\n'
                   f'      ECU_ADDRESS 0x{addr:08X}\n    /end MEASUREMENT')
    for v in PARAMS:
        addr = mem.addr[v[0]][0]
        layout = f"RL_{v[1]}"
        layouts.add((layout, v[1]))
        out.append(f'    /begin CHARACTERISTIC {v[0]} "{v[6]}" VALUE 0x{addr:08X} {layout} 0 {method(v)} {v[4]} {v[5]}\n'
                   f'    /end CHARACTERISTIC')
    for name, dtype in sorted(layouts):
        out.append(f'    /begin RECORD_LAYOUT {name}\n      FNC_VALUES 1 {dtype} COLUMN_DIR DIRECT\n    /end RECORD_LAYOUT')
    out.extend(methods.values())
    for name, table in vtabs.items():
        pairs = " ".join(f'{k} "{t}"' for k, t in table.items())
        out.append(f'    /begin COMPU_VTAB {name} "" TAB_VERB {len(table)}\n      {pairs}\n    /end COMPU_VTAB')
    out += ['  /end MODULE', '/end PROJECT', '']
    return "\n".join(out)


# ---------------------------------------------------------------------------
# XCP protocol
# ---------------------------------------------------------------------------
ERR_CMD_SYNCH, ERR_CMD_UNKNOWN, ERR_OUT_OF_RANGE, ERR_SEQUENCE, ERR_ACCESS_DENIED = 0x00, 0x20, 0x22, 0x29, 0x24
MAX_CTO, MAX_DTO = 255, 1024


class XcpSession:
    def __init__(self, mem, verbose=False):
        self.mem = mem
        self.connected = False
        self.mta = 0
        self.verbose = verbose
        o = mem.order
        self.u32 = lambda b: struct.unpack(o + "I", b)[0]

    def err(self, code):
        return bytes([0xFE, code])

    def handle(self, cto):
        pid = cto[0]
        if pid == 0xFF:  # CONNECT
            self.connected = True
            comm_mode = 0x01 if self.mem.order == ">" else 0x00
            return bytes([0xFF, 0x01, comm_mode, MAX_CTO]) + struct.pack(self.mem.order + "H", MAX_DTO) + bytes([1, 1])
        if not self.connected:
            return None  # slave stays silent until CONNECT
        try:
            if pid == 0xFE:  # DISCONNECT
                self.connected = False
                return b"\xFF"
            if pid == 0xFD:  # GET_STATUS
                return bytes([0xFF, 0, 0, 0, 0, 0])
            if pid == 0xFC:  # SYNCH
                return self.err(ERR_CMD_SYNCH)
            if pid == 0xFB:  # GET_COMM_MODE_INFO
                return bytes([0xFF, 0, 0, 0, 0, 0, 0, 0x10])
            if pid == 0xF6:  # SET_MTA
                self.mta = self.u32(cto[4:8])
                return b"\xFF"
            if pid == 0xF5:  # UPLOAD
                n = cto[1]
                data = self.mem.read(self.mta, n)
                self.mta += n
                return b"\xFF" + data
            if pid == 0xF4:  # SHORT_UPLOAD
                n = cto[1]
                if n > MAX_CTO - 1:
                    return self.err(ERR_OUT_OF_RANGE)
                return b"\xFF" + self.mem.read(self.u32(cto[4:8]), n)
            if pid == 0xF0:  # DOWNLOAD
                n = cto[1]
                self._write(self.mta, cto[2:2 + n])
                self.mta += n
                return b"\xFF"
            if pid == 0xED:  # SHORT_DOWNLOAD
                n = cto[1]
                self._write(self.u32(cto[4:8]), cto[8:8 + n])
                return b"\xFF"
            return self.err(ERR_CMD_UNKNOWN)
        except IndexError:
            return self.err(ERR_OUT_OF_RANGE)

    def _write(self, address, data):
        if not (PARAM_BASE <= address < PARAM_BASE + MEM_SIZE):
            raise PermissionError
        self.mem.write(address, data)
        if self.verbose:
            name = next((k for k, (a, _) in self.mem.addr.items() if a == address), hex(address))
            print(f"  calibrated {name} = {self.mem.get(name) if name in self.mem.addr else data.hex()}")


def frame(ctr, payload):
    return struct.pack("<HH", len(payload), ctr) + payload


def respond(session, data):
    """Handles all packets in one transport buffer, returns the response bytes."""
    out = b""
    pos = 0
    while pos + 4 <= len(data):
        length, ctr = struct.unpack_from("<HH", data, pos)
        pkt = data[pos + 4:pos + 4 + length]
        pos += 4 + length
        try:
            res = session.handle(pkt)
        except PermissionError:
            res = session.err(ERR_ACCESS_DENIED)
        if res is not None:
            out += frame(ctr, res)
    return out, pos


def serve_tcp(mem, host, port, verbose):
    class Handler(socketserver.BaseRequestHandler):
        def handle(self):
            print(f"[XCP/TCP] master connected from {self.client_address[0]}")
            session = XcpSession(mem, verbose)
            buf = b""
            self.request.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            while True:
                chunk = self.request.recv(4096)
                if not chunk:
                    break
                buf += chunk
                res, used = respond(session, buf)
                buf = buf[used:]
                if res:
                    self.request.sendall(res)
            print(f"[XCP/TCP] master {self.client_address[0]} disconnected")

    class Server(socketserver.ThreadingTCPServer):
        allow_reuse_address = True
        daemon_threads = True

    Server((host, port), Handler).serve_forever()


def serve_udp(mem, host, port, verbose):
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    sock.bind((host, port))
    sessions = {}
    while True:
        data, addr = sock.recvfrom(65535)
        session = sessions.setdefault(addr, XcpSession(mem, verbose))
        res, _ = respond(session, data)
        if res:
            sock.sendto(res, addr)


def serve_http(directory, host, port):
    handler = lambda *a, **kw: http.server.SimpleHTTPRequestHandler(*a, directory=directory, **kw)

    class Quiet(http.server.ThreadingHTTPServer):
        allow_reuse_address = True

    Quiet((host, port), handler).serve_forever()


def local_ips():
    ips = set()
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("10.255.255.255", 1))
        ips.add(s.getsockname()[0])
        s.close()
    except OSError:
        pass
    return sorted(ips) or ["127.0.0.1"]


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--host", default="0.0.0.0")
    ap.add_argument("--port", type=int, default=5555, help="XCP TCP and UDP port")
    ap.add_argument("--http-port", type=int, default=8080, help="port serving demo.a2l, 0 = off")
    ap.add_argument("--big-endian", action="store_true", help="simulate a Motorola byte order ECU")
    ap.add_argument("--rate", type=float, default=200.0, help="model steps per second")
    ap.add_argument("--a2l-only", action="store_true", help="only write demo.a2l and exit")
    ap.add_argument("-v", "--verbose", action="store_true", help="log calibration writes")
    args = ap.parse_args()

    mem = Memory(args.big_endian)
    here = os.path.dirname(os.path.abspath(__file__))
    with open(os.path.join(here, "demo.a2l"), "w", encoding="utf-8") as f:
        f.write(a2l_text(mem, args.big_endian, args.port))
    if args.a2l_only:
        print("demo.a2l written")
        return

    model = VehicleModel(mem)

    def run_model():
        dt = 1.0 / args.rate
        nxt = time.perf_counter()
        while True:
            model.step(dt)
            nxt += dt
            pause = nxt - time.perf_counter()
            if pause > 0:
                time.sleep(pause)
            else:
                nxt = time.perf_counter()

    threading.Thread(target=run_model, daemon=True).start()
    threading.Thread(target=serve_udp, args=(mem, args.host, args.port, args.verbose), daemon=True).start()
    if args.http_port:
        threading.Thread(target=serve_http, args=(here, args.host, args.http_port), daemon=True).start()
    ips = ", ".join(local_ips())
    print(f"XCP simulator running: XCP on TCP/UDP port {args.port} ({'Motorola' if args.big_endian else 'Intel'})")
    print(f"IP address(es) of this host: {ips}")
    if args.http_port:
        print(f"A2L: http://{local_ips()[0]}:{args.http_port}/demo.a2l")
    serve_tcp(mem, args.host, args.port, args.verbose)


if __name__ == "__main__":
    main()
