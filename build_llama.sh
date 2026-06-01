#!/bin/bash
set -e

export NDK="$HOME/android-ndk-r27c"
LLAMA_SRC="$HOME/llama.cpp"
BUILD_DIR="$LLAMA_SRC/build-android"

echo "=== Step 1: Check NDK ==="
ls "$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/clang"

echo "=== Step 2: Clean old build ==="
rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR"

echo "=== Step 3: CMake Configure ==="
cd "$LLAMA_SRC"
cmake \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM=26 \
  -DCMAKE_C_FLAGS="-march=armv8.2a" \
  -DCMAKE_CXX_FLAGS="-march=armv8.2a" \
  -DGGML_OPENMP=OFF \
  -DGGML_LLAMAFILE=OFF \
  -DBUILD_SHARED_LIBS=ON \
  -DLLAMA_CURL=OFF \
  -B "$BUILD_DIR" \
  -S .

echo "=== Step 4: Build ==="
cmake --build "$BUILD_DIR" --config Release -j$(nproc)

echo "=== Step 5: Install ==="
cmake --install "$BUILD_DIR" --prefix "$LLAMA_SRC/output" --config Release

echo "=== DONE ==="
echo "Output libraries:"
ls -la "$LLAMA_SRC/output/lib/"
