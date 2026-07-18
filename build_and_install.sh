#!/bin/bash
# ============================================================
# NF Touch One-Click Build & Install Script
# Auto-bump version → build → adb install
#
# Version scheme same as server: year.month.day.sequence (e.g. 2026.7.12.2)
# Usage: ./build_and_install.sh [device_serial]
# ============================================================

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
VERSION_FILE="$SCRIPT_DIR/VERSION"
BUILD_GRADLE="$SCRIPT_DIR/app/build.gradle.kts"
APK_DIR="$SCRIPT_DIR/app/build/outputs/apk/debug"

# ---- Colors ----
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

log()  { echo -e "${CYAN}[INFO]${NC} $*"; }
ok()   { echo -e "${GREEN}[ OK ]${NC} $*"; }
warn() { echo -e "${YELLOW}[WARN]${NC} $*"; }
die()  { echo -e "${RED}[FAIL]${NC} $*"; exit 1; }

# ---- 1. Auto-bump version ----
TODAY=$(date +%Y.%-m.%-d)
TODAY_SHORT=$(date +%y%m%d)  # For versionCode, e.g. 260712

CURRENT=$(cat "$VERSION_FILE" 2>/dev/null | tr -d '[:space:]')
if [[ -z "$CURRENT" ]]; then
    CURRENT="${TODAY}.0"
fi

if [[ "$CURRENT" == $TODAY.* ]]; then
    SEQ=$(echo "$CURRENT" | sed "s/$TODAY\.//")
    NEW_SEQ=$((SEQ + 1))
else
    NEW_SEQ=1
fi

NEW_VERSION="${TODAY}.${NEW_SEQ}"
echo "$NEW_VERSION" > "$VERSION_FILE"

# versionCode: yyMMddNN (2-digit year-month-day + 2-digit sequence), ensures monotonic increment
VERSION_CODE=$(( ${TODAY_SHORT} * 100 + NEW_SEQ ))

log "Version bump: ${CURRENT} → ${NEW_VERSION} (versionCode=${VERSION_CODE})"

# ---- 2. Update build.gradle.kts ----
if [[ ! -f "$BUILD_GRADLE" ]]; then
    die "Cannot find $BUILD_GRADLE"
fi

# Replace versionCode
sed -i '' "s/versionCode = [0-9]*/versionCode = ${VERSION_CODE}/" "$BUILD_GRADLE"
# Replace versionName
sed -i '' "s/versionName = \"[^\"]*\"/versionName = \"${NEW_VERSION}\"/" "$BUILD_GRADLE"

ok "build.gradle.kts 已更新: versionCode=${VERSION_CODE}, versionName=\"${NEW_VERSION}\""

# ---- 3. Build ----
log "Building Debug APK ..."
cd "$SCRIPT_DIR"

export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"

if ./gradlew assembleDebug -q 2>&1; then
    ok "Build successful"
else
    die "Build failed, check error output"
fi

# ---- 4. Find APK ----
APK_FILE=$(ls -t "$APK_DIR"/*.apk 2>/dev/null | head -1)
if [[ -z "$APK_FILE" ]]; then
    die "APK artifact not found"
fi

APK_SIZE=$(du -h "$APK_FILE" | cut -f1)
log "APK: $(basename "$APK_FILE") ($APK_SIZE)"

# ---- 5. adb install ----
ADB_ARGS=""
if [[ -n "$1" ]]; then
    ADB_ARGS="-s $1"
    log "Target device: $1"
fi

# Check adb connection
if ! adb $ADB_ARGS get-state >/dev/null 2>&1; then
    die "No adb device detected. Connect phone and enable USB debugging"
fi

DEVICE_MODEL=$(adb $ADB_ARGS shell getprop ro.product.model 2>/dev/null | tr -d '\r')
log "Target device: $DEVICE_MODEL"

log "Installing APK ..."
if adb $ADB_ARGS install -r -d "$APK_FILE" 2>&1; then
    ok "Install successful! v${NEW_VERSION} deployed to device"
else
    die "Install failed"
fi

echo ""
echo -e "${GREEN}==============================${NC}"
echo -e "${GREEN}  Done! v${NEW_VERSION} (${VERSION_CODE})${NC}"
echo -e "${GREEN}  Device: ${DEVICE_MODEL}${NC}"
echo -e "${GREEN}  APK:  $(basename "$APK_FILE")${NC}"
echo -e "${GREEN}==============================${NC}"
