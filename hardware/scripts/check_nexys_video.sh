#!/usr/bin/env bash
# Read-only FPGA environment preflight. No flashing, execution, or result creation.
set -euo pipefail
printf '===== NEXYS VIDEO FPGA ENVIRONMENT =====\n'
for cmd in git python3 lsusb openFPGALoader vivado riscv64-unknown-elf-gcc; do
  if command -v "$cmd" >/dev/null 2>&1; then
    printf 'FOUND   %-27s %s\n' "$cmd" "$(command -v "$cmd")"
  else
    printf 'MISSING %-27s\n' "$cmd"
  fi
done
printf '\n===== USB / SERIAL DEVICES =====\n'
if command -v lsusb >/dev/null 2>&1; then
  lsusb | grep -Ei '0403:6010|0403:6001|FTDI|Digilent' || true
fi
for dev in /dev/ttyUSB*; do
  [[ ! -e "$dev" ]] || ls -l "$dev"
done
printf '\n===== OPTIONAL USER-SUPPLIED ARTIFACT PATHS =====\n'
for pair in \
  "FPGA_BITSTREAM:${FPGA_BITSTREAM:-}" \
  "UART_TSI:${UART_TSI:-}" \
  "TEST_ELF:${TEST_ELF:-}" \
  "WORKLOAD_ELF:${WORKLOAD_ELF:-}" \
  "CHIPYARD_DIR:${CHIPYARD_DIR:-}"; do
  label="${pair%%:*}"; path="${pair#*:}"
  if [[ -z "$path" ]]; then
    printf 'UNSET   %s\n' "$label"
  elif [[ -e "$path" ]]; then
    printf 'PRESENT %-20s %s\n' "$label" "$path"
  else
    printf 'MISSING %-20s %s\n' "$label" "$path"
  fi
done
printf '\nNOTE: File presence alone is not physical FPGA reproducibility.\n'
