#!/bin/bash
# ================================================================
# Hermes Control App — AndroidIDE Auto Setup
# اجرا کن: source setup_androidide.sh
# ================================================================

PROJECT="/root/hermes-control-app"
echo "📱 Hermes Control — AndroidIDE Setup"
echo "========================================"

# 1. Check AndroidIDE is installed
if ! command -V am 2>/dev/null && ! pm list packages 2>/dev/null | grep -q "com.itsaky.androidide"; then
    echo "❌ AndroidIDE not found. Install from F-Droid first!"
    echo "   https://d.andronix.app/AndroidIDE.apk"
    exit 1
fi

echo "✅ AndroidIDE detected"

# 2. Copy project to internal storage
DEST="/storage/emulated/0/AndroidIDEProjects/HermesControl"
cp -r "$PROJECT" "$DEST" 2>/dev/null || {
    mkdir -p "$DEST"
    cp -r "$PROJECT"/* "$DEST/"
}
echo "✅ Project copied to $DEST"

# 3. Open AndroidIDE with the project
echo ""
echo "========================================"
echo "📋 BUILD GUIDE:"
echo "========================================"
echo ""
echo "1️⃣ Open AndroidIDE app"
echo "   → Open Project → $DEST"
echo ""
echo "2️⃣ Let Gradle sync finish"
echo "   (Wait for: BUILD SUCCESSFUL)"
echo ""
echo "3️⃣ Connect your phone (Shizuku needs setup)"
echo "   - Install Shizuku app from lsposed.github.io"
echo "   - Run ADB command ONCE: adb shell sh /data/adb/moe.shizuku.privileged.api/start.sh"
echo ""
echo "4️⃣ Run → Run App → Install on device"
echo "   APK will be built and installed automatically!"
echo ""
echo "========================================"
echo "🔗 After install:"
echo "   1. Open Hermes Control app"
echo "   2. Grant Shizuku permission when prompted"
echo "   3. Tap 'Connect' to load Hermes Web Dashboard"
echo "   4. In Termux: hermes mcp add android --url http://127.0.0.1:9199"
echo ""
echo "🎯 Done! Hermes can now control your phone via the app"
echo "========================================"
