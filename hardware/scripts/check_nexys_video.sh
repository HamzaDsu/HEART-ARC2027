#!/usr/bin/env bash
# Read-only preflight: no flash, UART write, result files, or repo changes.
set -euo pipefail

printf '===== NEXYS VIDEO FPGA ENVIRONMENT =====\n'
for name in git python3 lsusb openFPGALoader vivado riscv64-unknown-elf-gcc; do
  if command -v "$name" >/dev/null 2>&1; then
    printf 'FOUND   %-28s %s\n' "$name" "$(command -v "$name")"
  else
    printf 'MISSING %-28s\n' "$name"
  fi
done
printf '\n===== DEVICES =====\n'
if command -v lsusb >/dev/null 2>&1; then
  lsusb | grep -Ei '0403:6010|0403:6001|FTDI|Digilent' || true
fi
for dev in /dev/ttyUSB*; do
  [[ -e "$dev" ]] && ls -l "$dev" || true
done
printf '\n===== LOCAL LAB ARTIFACTS (NOT SHIPPED) =====\n'
for p in \
  "$HOME/nexys-video-sunday-deployment/fpga/UnifiedResource1Core16K50NexysVideoConfig.bit" \
  "$HOME/nexys-video-sunday-deployment/fpga/uart_tsi" \
  "$HOME/nexys-video-sunday-deployment/fpga/hello.riscv" \
  "$HOME/levit384-fpga-build/levit384-port/levit384_cyclehex_fpga.riscv" \
  "$HOME/chipyard"; do
  if [[ -e "$p" ]]; then
    echo "PRESENT $p"
  else
    echo "MISSING $p"
  fi
done
printf '\nNOTE: Presence is not a full FPGA reproduction check.\n'
