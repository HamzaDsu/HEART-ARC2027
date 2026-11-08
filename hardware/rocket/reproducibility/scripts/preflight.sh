#!/usr/bin/env bash
set -uo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FAILURES=0

echo "===== HEALTH-SLM BOOTSTRAP PREFLIGHT ====="

echo
echo "===== PLATFORM ====="

if [[ "$(uname -s)" == "Linux" &&
      "$(uname -m)" == "x86_64" ]]; then
    echo "PASS: Linux x86-64"
else
    echo "FAIL: Requires Linux x86-64"
    FAILURES=$((FAILURES + 1))
fi

GLIBC="$(getconf GNU_LIBC_VERSION | awk '{print $2}')"
echo "System glibc: $GLIBC"

if [[ "$(printf '%s\n' 2.39 "$GLIBC" | sort -V | head -1)" == "2.39" ]]; then
    echo "PASS: glibc requirement"
else
    echo "FAIL: glibc 2.39 or newer required"
    FAILURES=$((FAILURES + 1))
fi

echo
echo "===== DEVELOPMENT TOOLS ====="

for TOOL in \
    git gcc g++ make cmake ninja \
    python3 conda bazelisk \
    riscv64-linux-gnu-gcc riscv64-linux-gnu-g++ \
    autoconf automake flex bison \
    gawk pkg-config file sha256sum timeout; do

    if command -v "$TOOL" >/dev/null 2>&1; then
        echo "PASS: $TOOL"
    else
        echo "FAIL: Missing $TOOL"
        FAILURES=$((FAILURES + 1))
    fi
done

echo
echo "===== REPRODUCIBILITY FILES ====="

for FILE in \
    inventory/chipyard-conda-explicit-linux-64.txt \
    inventory/chipyard-conda-explicit-sha256.txt \
    inventory/chipyard-expected-tree.txt \
    inventory/health-slm-model.sha256; do

    if [[ -s "$REPO/$FILE" ]]; then
        echo "PASS: $FILE"
    else
        echo "FAIL: Missing $FILE"
        FAILURES=$((FAILURES + 1))
    fi
done

echo
echo "===== CONDA SNAPSHOT INTEGRITY ====="

if (cd "$REPO" &&
    sha256sum -c inventory/chipyard-conda-explicit-sha256.txt); then
    echo "PASS: Conda snapshot checksum"
else
    echo "FAIL: Conda snapshot integrity"
    FAILURES=$((FAILURES + 1))
fi

echo
echo "===== AVAILABLE DISK SPACE ====="

df -h "$HOME" | tail -1

echo
echo "===== PREFLIGHT SUMMARY ====="
echo "Failed checks: $FAILURES"

if [[ "$FAILURES" -ne 0 ]]; then
    echo "RESULT: PREFLIGHT FAILED"
    exit 1
fi

echo "RESULT: PREFLIGHT PASSED"
