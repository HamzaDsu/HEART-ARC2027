#!/usr/bin/env python3
import argparse
import os
import struct
import sys
import threading
import time

try:
    import serial
except ImportError:
    print("ERROR: pyserial not installed. Install with: pip3 install pyserial", file=sys.stderr)
    sys.exit(1)

SUCCESS_STRING = b"execution succeeded"
ANSI_RESET = b"\x1b[0m\x1b[?25h"

def read_file(path: str) -> bytes:
    with open(path, "rb") as f:
        return f.read()

def send_frame(ser: serial.Serial, tag4: bytes, payload: bytes, require_word_aligned: bool = False):
    if len(tag4) != 4:
        raise ValueError("tag must be 4 bytes")
    if require_word_aligned and (len(payload) % 4 != 0):
        raise ValueError(f"{tag4!r} payload length must be multiple of 4")

    header = tag4 + struct.pack("<I", len(payload))
    ser.write(header)
    ser.flush()

    CHUNK = 4096
    for i in range(0, len(payload), CHUNK):
        ser.write(payload[i:i + CHUNK])
    ser.flush()

def wait_for_byte(ser: serial.Serial, expected: int, timeout_s: float) -> bool:
    deadline = time.time() + timeout_s
    while time.time() < deadline:
        b = ser.read(1)
        if not b:
            continue
        if b[0] == expected:
            return True
    return False

def rx_printer(ser: serial.Serial, stop_evt: threading.Event, escape: bool):
    r"""
    Continuously read from UART and print to stdout.
    Terminates when SUCCESS_STRING is received.

    When escape=False (default), raw bytes are written directly to stdout so
    the terminal interprets ANSI escape sequences natively (cursor movement,
    colors, clearing, etc.).

    When escape=True, non-printable bytes are hex-escaped for log-friendly
    output (e.g. ``\x1b[2J`` appears as literal text).
    """
    printable = set(range(0x20, 0x7F)) | {0x0A, 0x0D, 0x09}
    out_buf = sys.stdout.buffer
    out_text = sys.stdout
    success_buf = bytearray()

    while not stop_evt.is_set():
        b = ser.read(1)
        if not b:
            continue

        success_buf.append(b[0])
        if len(success_buf) > len(SUCCESS_STRING):
            success_buf.pop(0)

        if bytes(success_buf) == SUCCESS_STRING:
            print("\n[HOST] execution succeeded detected — terminating.", file=sys.stderr)
            stop_evt.set()
            break

        if escape:
            v = b[0]
            if v in printable:
                out_text.write(chr(v))
            else:
                out_text.write(f"\\x{v:02x}")
            out_text.flush()
        else:
            out_buf.write(b)
            out_buf.flush()

def main():
    ap = argparse.ArgumentParser(
        description="UART loader: send IMEM+DMEM and terminate on 'execution succeeded'.",
        usage="%(prog)s [program_dir] [--imem FILE] [--dmem FILE] [--port PORT] [options]",
        epilog="Examples:\n"
               "  %(prog)s software/hello --port /dev/ttyUSB1   (after make -C software/hello)\n"
               "  %(prog)s --imem path/to/instruction_mem.bin --dmem path/to/data_mem.bin\n",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    ap.add_argument(
        "program_dir", nargs="?", default=None,
        help="Directory containing instruction_mem.bin and data_mem.bin. "
             "Explicit --imem/--dmem flags override files from this directory.",
    )
    ap.add_argument("--port", default="/dev/ttyUSB3")
    ap.add_argument("--baud", type=int, default=115200)
    ap.add_argument("--imem", default=None,
                    help="Path to instruction memory binary (overrides program_dir)")
    ap.add_argument("--dmem", default=None,
                    help="Path to data memory binary (overrides program_dir)")
    ap.add_argument("--delay", type=float, default=0.2)
    ap.add_argument(
        "--escape", action="store_true",
        help="Hex-escape non-printable bytes instead of passing them through. "
             "Useful when redirecting output to a log file.",
    )
    ap.add_argument("--no-dmem", action="store_true")
    ap.add_argument("--wait-timeout", type=float, default=3.0)
    args = ap.parse_args()

    imem_path = args.imem
    dmem_path = args.dmem

    if args.program_dir:
        d = args.program_dir.rstrip("/")
        if imem_path is None:
            imem_path = os.path.join(d, "instruction_mem.bin")
        if dmem_path is None:
            dmem_path = os.path.join(d, "data_mem.bin")

    if imem_path is None:
        print("ERROR: No program directory or --imem specified.", file=sys.stderr)
        ap.print_usage(sys.stderr)
        return 2
    if dmem_path is None and not args.no_dmem:
        print("ERROR: No program directory or --dmem specified.", file=sys.stderr)
        ap.print_usage(sys.stderr)
        return 2

    if not os.path.exists(imem_path):
        print(f"ERROR: Missing {imem_path}", file=sys.stderr)
        return 2
    if not args.no_dmem and not os.path.exists(dmem_path):
        print(f"ERROR: Missing {dmem_path}", file=sys.stderr)
        return 2

    imem = read_file(imem_path)
    dmem = b"" if args.no_dmem else read_file(dmem_path)

    rem = len(imem) % 4
    if rem == 2:
        print(f"[HOST] IMEM padded with 2 zero bytes (C-ext tail half-word; "
              f"{len(imem)} -> {len(imem)+2}).", file=sys.stderr)
        imem = imem + b"\x00\x00"
    elif rem != 0:
        print(f"ERROR: IMEM length {len(imem)} bytes has remainder {rem} mod 4; "
              f"valid RISC-V instructions are 16- or 32-bit so this can only "
              f"occur with a corrupted build. Refusing to pad.", file=sys.stderr)
        return 2

    ser = serial.Serial(
        port=args.port,
        baudrate=args.baud,
        bytesize=serial.EIGHTBITS,
        parity=serial.PARITY_NONE,
        stopbits=serial.STOPBITS_ONE,
        timeout=0.1,
        write_timeout=2.0,
    )

    ser.reset_input_buffer()
    ser.reset_output_buffer()

    try:
        time.sleep(args.delay)

        print(f"[HOST] Sending IMEM ({len(imem)} bytes)...", file=sys.stderr)
        send_frame(ser, b"IMEM", imem, require_word_aligned=True)
        print("[HOST] Waiting for 'I'...", file=sys.stderr)
        wait_for_byte(ser, ord('I'), args.wait_timeout)

        if not args.no_dmem:
            print(f"[HOST] Sending DMEM ({len(dmem)} bytes)...", file=sys.stderr)
            send_frame(ser, b"DMEM", dmem)
            print("[HOST] Waiting for 'O'...", file=sys.stderr)
            wait_for_byte(ser, ord('O'), args.wait_timeout)

        stop_evt = threading.Event()
        t = threading.Thread(target=rx_printer, args=(ser, stop_evt, args.escape), daemon=True)
        t.start()

        while not stop_evt.is_set():
            time.sleep(0.1)

    except KeyboardInterrupt:
        print("\n[HOST] Interrupted.", file=sys.stderr)
    finally:
        try:
            stop_evt.set()
            t.join(timeout=0.5)
        except Exception:
            pass
        ser.close()
        if not args.escape:
            sys.stdout.buffer.write(ANSI_RESET)
            sys.stdout.buffer.flush()

    return 0

if __name__ == "__main__":
    raise SystemExit(main())
