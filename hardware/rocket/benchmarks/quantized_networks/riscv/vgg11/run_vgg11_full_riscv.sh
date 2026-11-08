#!/usr/bin/env bash
set -euo pipefail

cd "$HOME/health-slm-riscv-hardware"

PYTHON="$HOME/.venvs/mahdi-four-models-cpu/bin/python"

env -u PYTHONPATH -u PYTHONHOME PYTHONNOUSERSITE=1 \
  "$PYTHON" -I \
  benchmarks/quantized_networks/riscv/vgg11/vgg11_full_riscv.py
