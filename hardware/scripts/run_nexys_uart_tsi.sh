#!/usr/bin/env bash
# Explicit local Rocket bitstream/ELF runner; never ships or generates binaries.
set -euo pipefail

usage() {
  cat <<'HELP'
Usage:
  run_nexys_uart_tsi.sh --bitstream FILE --uart-tsi FILE --elf FILE \
      --port /dev/ttyUSBX [--flash] [--timeout SECONDS] [--dry-run]

--flash    Detect/program the compatible FPGA bitstream to SRAM first.
--dry-run  Show the intended command without opening the board or flashing.
          The supplied bitstream and ELF must be from the SAME board design.

Caution: Matches the known 16 KiB Rocket UART-TSI invocation pattern.
UART baud rate must match the actual bitstream (standard vs FastUART).
HELP
}
BIT=""; TSI=""; ELF=""; PORT=""; FLASH=0; DRY=0; LIMIT=3600
while (($#)); do
  case "$1" in
    --bitstream|--uart-tsi|--elf|--port|--timeout)
      KEY="$1"; shift
      (($#)) || { echo "Missing value for $KEY" >&2; exit 2; }
      case "$KEY" in
        --bitstream) BIT="$1" ;;
        --uart-tsi) TSI="$1" ;;
        --elf) ELF="$1" ;;
        --port) PORT="$1" ;;
        --timeout) LIMIT="$1" ;;
      esac
      ;;
    --flash) FLASH=1 ;;
    --dry-run) DRY=1 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown option: $1" >&2; usage >&2; exit 2 ;;
  esac
  shift
done
[[ -n "$BIT" && -n "$TSI" && -n "$ELF" && -n "$PORT" ]] || { usage >&2; exit 2; }
[[ "$LIMIT" =~ ^[1-9][0-9]*$ ]] || { echo 'Timeout must be positive integer' >&2; exit 2; }

if ((DRY)); then
  printf 'DRY RUN: planned inputs (not validated)\n'
  printf '  bitstream: %s\n  UART-TSI: %s\n  ELF: %s\n  port: %s\n' "$BIT" "$TSI" "$ELF" "$PORT"
  if ((FLASH)); then printf '  flash command: '; printf '%q ' openFPGALoader -b nexysVideo -m "$BIT"; printf '\n'; fi
  printf '  loader command: '; printf '%q ' timeout --foreground "${LIMIT}s" "$TSI" "+tty=$PORT" +selfcheck "$ELF"; printf '\n'
  exit 0
fi

[[ -s "$BIT" ]] || { echo "Missing bitstream: $BIT" >&2; exit 1; }
[[ -x "$TSI" ]] || { echo "Missing executable uart_tsi: $TSI" >&2; exit 1; }
[[ -s "$ELF" ]] || { echo "Missing RISC-V ELF: $ELF" >&2; exit 1; }
[[ -c "$PORT" ]] || { echo "Not a serial character device: $PORT" >&2; exit 1; }
[[ -r "$PORT" && -w "$PORT" ]] || { echo "No access to $PORT (dialout group or sudo needed)" >&2; exit 1; }
command -v timeout >/dev/null || { echo 'Missing timeout' >&2; exit 1; }
if command -v file >/dev/null; then
  file "$ELF"
  file "$ELF" | grep -qi 'RISC-V' || { echo 'ELF not recognized as RISC-V; aborting' >&2; exit 1; }
fi
sha256sum "$BIT" "$ELF"

if ((FLASH)); then
  command -v openFPGALoader >/dev/null || { echo 'openFPGALoader not available' >&2; exit 1; }
  openFPGALoader --detect -b nexysVideo
  openFPGALoader -b nexysVideo -m "$BIT"
fi
printf '\nStarting physical UART-TSI load; reset board first if rerunning.\n'
timeout --foreground "${LIMIT}s" "$TSI" "+tty=$PORT" +selfcheck "$ELF"
