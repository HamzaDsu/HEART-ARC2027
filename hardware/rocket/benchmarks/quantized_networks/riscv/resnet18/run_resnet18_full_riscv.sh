#!/usr/bin/env bash
set -euo pipefail
here="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
script="$here/resnet18_full_riscv.py"
if [[ ! -f "$script" ]]; then echo "ERROR: resnet18_full_riscv.py missing beside this launcher" >&2; exit 2; fi
python="$HOME/.venvs/mahdi-four-models-cpu/bin/python"
if [[ ! -x "$python" ]]; then echo "ERROR: Model Python environment not found: $python" >&2; exit 2; fi
exec env -u PYTHONPATH -u PYTHONHOME PYTHONNOUSERSITE=1 "$python" -I "$script"
