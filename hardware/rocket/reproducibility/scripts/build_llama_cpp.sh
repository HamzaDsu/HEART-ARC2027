#!/usr/bin/env bash
set -euo pipefail

MODE="${1:-}"
TARGET="${2:-}"
SOURCE="${3:-}"
DEST="${4:-}"

PIN="a868c3e3c56657f7e8a6231190dbbe90e7dd86c0"

if [[ "$MODE" != "--check" && "$MODE" != "--build" ]] ||
   [[ "$TARGET" != "native" && "$TARGET" != "riscv-linux" ]] ||
   [[ -z "$SOURCE" || -z "$DEST" ]]; then
    echo "Usage: $0 --check|--build native|riscv-linux SOURCE DEST"
    exit 1
fi

echo "===== VERIFY BUILD TOOLS ====="

for TOOL in git cmake ninja; do
    command -v "$TOOL" >/dev/null
    echo "PASS: $TOOL"
done

if [[ "$TARGET" == "native" ]]; then
    CC=/usr/bin/cc
    CXX=/usr/bin/c++
else
    CC=/usr/bin/riscv64-linux-gnu-gcc
    CXX=/usr/bin/riscv64-linux-gnu-g++
fi

test -x "$CC"
test -x "$CXX"

echo "PASS: $CC"
echo "PASS: $CXX"

echo "===== VERIFY SOURCE REVISION ====="

if [[ -d "$SOURCE/.git" ]]; then
    git -C "$SOURCE" cat-file -e "$PIN^{commit}"
    echo "PASS: Pinned llama.cpp commit available locally"
fi

[[ "$DEST" == /* ]] || {
    echo "ERROR: Destination must be an absolute path"
    exit 1
}

[[ ! -e "$DEST" ]] || {
    echo "ERROR: Destination already exists"
    exit 1
}

echo "Target: $TARGET"
echo "Revision: $PIN"
echo "Jobs: 2"

if [[ "$MODE" == "--check" ]]; then
    echo "===== LLAMA.CPP BUILD PRECHECK PASSED ====="
    exit 0
fi

echo "===== CLONE PINNED LLAMA.CPP ====="

git clone --no-checkout "$SOURCE" "$DEST"
git -C "$DEST" checkout --quiet --detach "$PIN"

test "$(git -C "$DEST" rev-parse HEAD)" = "$PIN"

echo "===== CONFIGURE BUILD ====="

CMAKE_ARGS=(
    -G Ninja
    -DCMAKE_BUILD_TYPE=Release
    "-DCMAKE_C_COMPILER=$CC"
    "-DCMAKE_CXX_COMPILER=$CXX"
    -DGGML_NATIVE=OFF
    -DGGML_BLAS=OFF
    -DGGML_CUDA=OFF
    -DLLAMA_BUILD_TESTS=OFF
    -DLLAMA_BUILD_EXAMPLES=ON
)

if [[ "$TARGET" == "riscv-linux" ]]; then
    CMAKE_ARGS+=(
        -DCMAKE_SYSTEM_NAME=Linux
        -DBUILD_SHARED_LIBS=OFF
        -DGGML_OPENMP=OFF
        -DGGML_RVV=OFF
    )
else
    CMAKE_ARGS+=(
        -DBUILD_SHARED_LIBS=ON
        -DLLAMA_BUILD_SERVER=ON
        -DGGML_AVX=OFF
        -DGGML_AVX2=OFF
        -DGGML_AVX512=OFF
        -DGGML_FMA=OFF
        -DGGML_F16C=OFF
    )
fi

cmake -S "$DEST" -B "$DEST/build" "${CMAKE_ARGS[@]}"

echo "===== COMPILE LLAMA.CPP ====="

if [[ "$TARGET" == "native" ]]; then
    cmake --build "$DEST/build" \
        --target llama-cli llama-server --parallel 2
else
    cmake --build "$DEST/build" \
        --target llama-cli --parallel 2
fi

echo "===== VERIFY EXECUTABLE ====="

test -x "$DEST/build/bin/llama-cli"
file "$DEST/build/bin/llama-cli"

echo "===== LLAMA.CPP BUILD COMPLETE ====="
