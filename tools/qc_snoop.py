#!/usr/bin/env python3
"""Decode QC/Oudmon ring traffic (QRing-family Colmi/Yawell rings) out of an Android HCI log.

Pull the log from a phone with "Bluetooth HCI snoop log" set to *Enabled* (not Filtered — the
filtered log truncates every ATT payload to a few bytes):

    adb bugreport bug.zip
    unzip -o bug.zip 'FS/data/misc/bluetooth/logs/*'
    python3 tools/qc_snoop.py FS/data/misc/bluetooth/logs/btsnoop_hci.log*

Several files (the live log and its rotated `.last`) are merged in time order. The ring's two
channels are recognised by content rather than by GATT handle, so traffic from earbuds, cars and
other peripherals in the same log is skipped:

  * UART   — 16-byte frames whose last byte is the low byte of the sum of the other 15
             (write 6e400002 / notify 6e400003).
  * BIG    — `bc <action> <len u16le> <crc u16le> <payload…>` frames, reassembled across
             notifications (write de5bf72a / notify de5bf729).

Opcode names come from the vendor SDK (`com.oudmon.ble.base.communication.Constants` and
`LargeDataHandler.respMap`, decompiled-qring-official/) and from PulseLoop's ColmiProtocol.kt.
Python 3 standard library only.
"""
import argparse
import collections
import datetime
import struct
import sys

# btsnoop timestamps count microseconds from 0000-01-01; this is 1970-01-01 on that scale.
BTSNOOP_EPOCH_DELTA_US = 0x00DCDDB30F2F8000

UART_NAMES = {
    0x01: "SET_TIME", 0x02: "TAKE_PICTURE", 0x03: "BATTERY", 0x04: "SET_PHONE_OS",
    0x05: "DISPLAY_PREF", 0x06: "MUTE", 0x07: "STEP_TOTAL_DAY", 0x08: "REBOOT/POWER_OFF",
    0x0A: "PREFERENCES", 0x0C: "BP_TIMING_SWITCH", 0x0D: "HR_TIMING_DATA", 0x0E: "HR_TIMING_CONFIRM",
    0x10: "BIND_SUCCESS", 0x11: "PHONE_NOTIFY", 0x13: "GET_SPORT", 0x14: "BP_READ",
    0x15: "HR_HISTORY", 0x16: "AUTO_HR_PREF", 0x19: "DEGREE_SWITCH", 0x1E: "REALTIME_HR",
    0x21: "GOALS", 0x22: "FIND_PHONE", 0x25: "SET_SIT_LONG", 0x26: "GET_SIT_LONG",
    0x27: "SET_DRINK", 0x28: "GET_DRINK", 0x2B: "MENSTRUATION", 0x2C: "AUTO_SPO2_PREF",
    0x2F: "PACKET_SIZE", 0x36: "AUTO_STRESS_PREF", 0x37: "STRESS_HISTORY", 0x38: "AUTO_HRV_PREF",
    0x39: "HRV_HISTORY", 0x3A: "AUTO_TEMP_PREF", 0x3B: "TOUCH/GESTURE", 0x3C: "DEVICE_SUPPORT",
    0x43: "ACTIVITY_DAY", 0x44: "GET_SLEEP", 0x48: "STEP_TODAY", 0x50: "FIND_DEVICE",
    0x51: "NOTIFICATION_EVENT", 0x52: "MUSLIM_REMIND", 0x60: "ANCS_SET", 0x61: "ANCS_GET",
    0x69: "MEASURE_START", 0x6A: "MEASURE_STOP", 0x72: "PUSH_MSG", 0x73: "DEVICE_NOTIFY",
    0x77: "PHONE_SPORT", 0x78: "SPORT_NOTIFY", 0x93: "HW_FW_VERSION", 0xA1: "DEVICE_REVISION",
    0xFF: "FACTORY_RESET",
}
# Second byte of 0x69/0x6A selects what is measured.
MEASURE_KINDS = {0x01: "hr", 0x02: "bp", 0x03: "spo2", 0x04: "fatigue", 0x05: "health_check",
                 0x06: "realtime_hr", 0x07: "ecg", 0x08: "pressure", 0x09: "blood_sugar",
                 0x0A: "hrv", 0x0B: "temperature"}
BIG_NAMES = {
    0x20: "DEVICE_LOCATION", 0x25: "TEMPERATURE", 0x27: "SLEEP", 0x28: "MANUAL_HR_LIST",
    0x29: "CONTACTS", 0x2A: "SPO2", 0x2C: "ALARM", 0x2E: "CLASSIC_BT", 0x2F: "QR_CODE",
    0x3A: "CUSTOM_WATCH", 0x3E: "SLEEP_LUNCH", 0x47: "BLOOD_SUGAR", 0x49: "MANUAL_SPO2_LIST",
    # From the SDK's addHeader() callers: FileHandle.start, SportPlusHandle.cmdSummary/cmdRequest,
    # LargeDataHandler.setDeviceNickName.
    0x30: "FILE_TRANSFER_START", 0x41: "SPORT_SUMMARY", 0x42: "SPORT_SUMMARY_REPLY",
    0x43: "SPORT_DETAIL", 0x4A: "DEVICE_NICKNAME",
    0x76: "EMERGENCY_CONTACT", 0x77: "INTERVAL_TEMPERATURE", 0x78: "SINGLE_ECG",
}


def read_btsnoop(path):
    """Yield (unix_us, sent_by_host, h4_packet) for every record in one btsnoop file."""
    data = open(path, "rb").read()
    if data[:8] != b"btsnoop\x00":
        raise SystemExit(f"{path}: not a btsnoop file")
    datalink = struct.unpack_from(">I", data, 12)[0]
    if datalink != 1002:
        raise SystemExit(f"{path}: datalink {datalink}, expected 1002 (H4)")
    pos = 16
    while pos + 24 <= len(data):
        _orig, incl, flags, _drops, ts = struct.unpack_from(">IIIIq", data, pos)
        pos += 24
        pkt = data[pos:pos + incl]
        pos += incl
        # flags bit 0: 0 = sent (host -> controller), 1 = received
        yield ts - BTSNOOP_EPOCH_DELTA_US, (flags & 1) == 0, pkt


def is_uart_frame(b):
    return len(b) == 16 and sum(b[:15]) & 0xFF == b[15]


def describe_uart(b):
    op = b[0]
    name = UART_NAMES.get(op & 0x7F, f"0x{op & 0x7F:02X}")
    if op & 0x80 and op != 0xFF and (op & 0x7F) in UART_NAMES:
        name += " ERROR"
    if op in (0x69, 0x6A):
        name += f"[{MEASURE_KINDS.get(b[1], hex(b[1]))}]"
    return name


class Decoder:
    def __init__(self, addr_filter=None):
        self.addr_filter = addr_filter.lower() if addr_filter else None
        self.peer = {}                      # ACL handle -> peer address string
        self.acl_buf = {}                   # (handle, sent) -> [expected_len, bytes]
        self.att_role = {}                  # (handle, att_handle) -> "UART" | "BIG"
        self.big = {}                       # (handle, att_handle) -> [expected_len, bytes, t0]
        self.rows = []

    def feed(self, t_us, sent, pkt):
        if not pkt:
            return
        kind = pkt[0]
        if kind == 0x04 and len(pkt) > 3:
            self._event(pkt[1], pkt[3:])
        elif kind == 0x02 and len(pkt) >= 5:
            self._acl(t_us, sent, pkt[1:])

    def _event(self, code, p):
        if code == 0x3E and p and p[0] in (0x01, 0x0A) and len(p) >= 12 and p[1] == 0:
            handle = struct.unpack_from("<H", p, 2)[0] & 0x0FFF
            self.peer[handle] = ":".join(f"{x:02X}" for x in reversed(p[6:12]))
        elif code == 0x05 and len(p) >= 3:
            self.peer.pop(struct.unpack_from("<H", p, 1)[0] & 0x0FFF, None)

    def _acl(self, t_us, sent, a):
        hdr, length = struct.unpack_from("<HH", a, 0)
        handle, pb = hdr & 0x0FFF, (hdr >> 12) & 0x3
        frag = a[4:4 + length]
        key = (handle, sent)
        if pb == 0x1:                                   # continuation
            if key not in self.acl_buf:
                return
            self.acl_buf[key][1] += frag
        else:                                           # start of an L2CAP PDU
            if len(frag) < 4:
                return
            self.acl_buf[key] = [struct.unpack_from("<H", frag, 0)[0] + 4, bytearray(frag)]
        need, buf = self.acl_buf[key]
        if len(buf) < need:
            return
        del self.acl_buf[key]
        cid = struct.unpack_from("<H", buf, 2)[0]
        if cid == 0x0004:
            self._att(t_us, sent, handle, bytes(buf[4:need]))

    def _att(self, t_us, sent, handle, att):
        if len(att) < 3 or att[0] not in (0x12, 0x52, 0x1B, 0x1D):
            return                                      # writes, notifications, indications
        peer = self.peer.get(handle, f"acl{handle:#x}")
        if self.addr_filter and self.addr_filter not in peer.lower():
            return
        att_handle = struct.unpack_from("<H", att, 1)[0]
        value = att[3:]
        key = (handle, att_handle)
        role = self.att_role.get(key)
        if role is None:
            if is_uart_frame(value):
                role = "UART"
            elif value[:1] == b"\xbc" and len(value) >= 6:
                role = "BIG"
            else:
                return
            self.att_role[key] = role
        if role == "UART":
            if is_uart_frame(value):
                self.rows.append((t_us, sent, "UART", describe_uart(value), value))
            return
        # BIG: start a frame on a `bc` header, otherwise append to the one in flight.
        state = self.big.get(key)
        if state is None or value[:1] == b"\xbc" and len(state[1]) >= state[0]:
            if value[:1] != b"\xbc" or len(value) < 6:
                return
            state = [struct.unpack_from("<H", value, 2)[0] + 6, bytearray(value), t_us]
            self.big[key] = state
        else:
            state[1] += value
        if len(state[1]) >= state[0]:
            frame = bytes(state[1][:state[0]])
            del self.big[key]
            name = BIG_NAMES.get(frame[1], f"0x{frame[1]:02X}")
            self.rows.append((state[2], sent, "BIG", f"{name} len={state[0] - 6}", frame))


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("logs", nargs="+", help="btsnoop_hci.log files (rotated .last too)")
    ap.add_argument("--addr", help="only this peer (substring of the BD address)")
    ap.add_argument("--after", help="local time HH:MM[:SS]; drop frames before it")
    ap.add_argument("--summary", action="store_true", help="count frames per opcode instead")
    ap.add_argument("--width", type=int, default=64, help="max hex chars per line (0 = all)")
    args = ap.parse_args()

    records = [r for path in args.logs for r in read_btsnoop(path)]
    records.sort(key=lambda r: r[0])
    dec = Decoder(args.addr)
    for t_us, sent, pkt in records:
        dec.feed(t_us, sent, pkt)

    rows = dec.rows
    if args.after:
        hh, mm, *ss = (int(x) for x in args.after.split(":"))
        rows = [r for r in rows
                if datetime.datetime.fromtimestamp(r[0] / 1e6).time() >= datetime.time(hh, mm, *(ss or [0]))]
    if args.summary:
        counts = collections.Counter((r[2], "->" if r[1] else "<-", r[3].split(" len=")[0]) for r in rows)
        for (chan, d, name), n in sorted(counts.items()):
            print(f"{chan:4} {d} {name:28} {n}")
        return
    for t_us, sent, chan, name, frame in rows:
        ts = datetime.datetime.fromtimestamp(t_us / 1e6).strftime("%H:%M:%S.%f")[:-3]
        hexs = frame.hex()
        if args.width and len(hexs) > args.width:
            hexs = hexs[:args.width] + f"…(+{len(frame) - args.width // 2}B)"
        print(f"{ts} {'->' if sent else '<-'} {chan:4} {name:28} {hexs}")


if __name__ == "__main__":
    sys.exit(main())
