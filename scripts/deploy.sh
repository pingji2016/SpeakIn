#!/bin/bash
# =============================================================================
# SpeakIn Deploy Script
# Builds the APK, installs to device, and pushes model files.
#
# Usage:
#   ./scripts/deploy.sh              # Full build + install + push models
#   ./scripts/deploy.sh --apk-only   # Only build and install APK
#   ./scripts/deploy.sh --models-only # Only push model files
#   ./scripts/deploy.sh --m4a FILE   # Push an M4A test file to device sdcard
# =============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

# ── Config ──────────────────────────────────────────────────────────────────
ASR_MODEL_FILES=(
    "app/src/main/assets/models/whisper/whisper_pre_enc.pte"
    "app/src/main/assets/models/whisper/whisper_decoder.pte"
    "app/src/main/assets/models/whisper/tokenizer.json"
)
LLM_MODEL_FILE="qwen3-0.6b-q4_k_m.gguf"
APP_PACKAGE="com.speakin.app"
WHISPER_DIR="files/whisper"
MODELS_DIR="files/models"

# ── Helpers ──────────────────────────────────────────────────────────────────
adb_shell() {
    MSYS_NO_PATHCONV=1 adb shell "$@"
}

adb_push() {
    MSYS_NO_PATHCONV=1 adb push "$@"
}

abort() {
    echo "ERROR: $*" >&2
    exit 1
}

check_device() {
    if ! adb devices 2>/dev/null | grep -q 'device$'; then
        abort "No Android device connected. Check 'adb devices'."
    fi
}

# ── Build APK ───────────────────────────────────────────────────────────────
build_apk() {
    echo "🔨 Building debug APK..."
    cd "$PROJECT_DIR"
    ./gradlew assembleOnlineDebug || abort "Build failed"
    echo "✅ APK built: app/build/outputs/apk/online/debug/app-online-debug.apk"
}

# ── Install APK ─────────────────────────────────────────────────────────────
install_apk() {
    echo "📦 Installing APK..."
    cd "$PROJECT_DIR"
    MSYS_NO_PATHCONV=1 adb install -r app/build/outputs/apk/online/debug/app-online-debug.apk
    echo "✅ APK installed"
}

# ── Push models ─────────────────────────────────────────────────────────────
push_asr_models() {
    echo "🎤 Pushing ASR (Whisper) models..."

    # Push files to sdcard first (world-readable)
    for local_file in "${ASR_MODEL_FILES[@]}"; do
        local fname
        fname=$(basename "$local_file")
        echo "  → $fname"
        adb_push "$PROJECT_DIR/$local_file" "/sdcard/$fname"
    done

    # Copy from sdcard to app internal storage via pipe
    for local_file in "${ASR_MODEL_FILES[@]}"; do
        local fname
        fname=$(basename "$local_file")
        adb_shell "cat /sdcard/$fname | run-as $APP_PACKAGE sh -c 'mkdir -p $WHISPER_DIR && cat > $WHISPER_DIR/$fname'"
        adb_shell "rm /sdcard/$fname"
    done

    echo "✅ ASR models pushed"
}

push_llm_model() {
    echo "🤖 Pushing LLM model..."

    # Check if LLM model exists on device sdcard
    local sdcard_gguf
    sdcard_gguf=$(adb_shell "ls /sdcard/*.gguf 2>/dev/null || echo ''" | head -1)

    if [ -n "$sdcard_gguf" ]; then
        local fname
        fname=$(basename "$sdcard_gguf")
        echo "  Found on sdcard: $fname"
        echo "  Copying to app storage..."
        adb_shell "cat /sdcard/$fname | run-as $APP_PACKAGE sh -c 'mkdir -p $MODELS_DIR && cat > $MODELS_DIR/$LLM_MODEL_FILE'"
        echo "✅ LLM model pushed from sdcard"
    else
        echo "⚠️  No .gguf file found on /sdcard/ — LLM model not pushed."
        echo "   Drop a $LLM_MODEL_FILE on the sdcard and re-run --models-only"
    fi
}

verify_models() {
    echo "🔍 Verifying model files..."
    echo ""
    echo "  ASR models:"
    adb_shell "run-as $APP_PACKAGE ls -la $WHISPER_DIR/" 2>/dev/null || echo "    (none)"
    echo ""
    echo "  LLM models:"
    adb_shell "run-as $APP_PACKAGE ls -la $MODELS_DIR/" 2>/dev/null || echo "    (none)"
    echo ""
    echo "✅ Verification complete"
}

# ── Main ────────────────────────────────────────────────────────────────────
MODE="full"
M4A_FILE=""

while [[ $# -gt 0 ]]; do
    case "$1" in
        --apk-only)
            MODE="apk"
            shift
            ;;
        --models-only)
            MODE="models"
            shift
            ;;
        --m4a)
            M4A_FILE="$2"
            shift 2
            ;;
        --help|-h)
            echo "Usage: $0 [--apk-only | --models-only | --m4a FILE]"
            exit 0
            ;;
        *)
            abort "Unknown option: $1 (try --help)"
            ;;
    esac
done

check_device

case "$MODE" in
    full)
        push_asr_models
        push_llm_model
        build_apk
        install_apk
        verify_models
        echo ""
        echo "🚀 Deploy complete! (APK + models)"
        ;;
    apk)
        build_apk
        install_apk
        echo ""
        echo "📦 APK install complete!"
        ;;
    models)
        push_asr_models
        push_llm_model
        verify_models
        echo ""
        echo "📦 Models pushed!"
        ;;
esac

# ── Optional: push M4A test file ────────────────────────────────────────────
if [ -n "$M4A_FILE" ]; then
    echo ""
    echo "🎵 Pushing test audio: $M4A_FILE"
    adb_push "$M4A_FILE" "/sdcard/$(basename "$M4A_FILE")"
    echo "✅ Test audio pushed"
fi
