#!/bin/bash
# Runs on CI against an Android TV emulator: installs the APK, adds a few sites and takes screenshots.
set -x
OUT=screens
mkdir -p "$OUT"
PKG=com.ahusan.webapps

shot() {
  sleep "${2:-2}"
  adb exec-out screencap -p > "$OUT/$1.png"
}

# Tap the on-screen element whose text matches $1 (found via a UI dump).
tap_text() {
  adb shell uiautomator dump /sdcard/ui.xml > /dev/null
  adb pull /sdcard/ui.xml ui.xml > /dev/null
  local xy
  xy=$(python3 - "$1" <<'EOF'
import re, sys
s = open('ui.xml', encoding='utf-8').read()
t = sys.argv[1]
for m in re.finditer(r'<node [^>]*>', s):
    n = m.group(0)
    if f'text="{t}"' in n:
        x1, y1, x2, y2 = map(int, re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n).groups())
        print((x1 + x2) // 2, (y1 + y2) // 2)
        break
EOF
)
  if [ -n "$xy" ]; then adb shell input tap $xy; else echo "not found: $1"; fi
}

add_site() {
  tap_text "Add website"
  sleep 2
  adb shell input text "$1"
  [ -n "$2" ] && shot "$2" 1
  adb shell input keyevent KEYCODE_ENTER   # keyboard Enter = Save
  sleep 2
}

adb install -r WebAppsTV.apk
adb shell wm size
# Turn off the on-screen keyboard so typed keys and Enter go straight to the app.
for ime in $(adb shell ime list -s | tr -d "\r"); do adb shell ime disable "$ime"; done
adb shell am start -n $PKG/.MainActivity
shot 01_home_empty 5

add_site wikipedia.org 02_add_website
add_site youtube.com
add_site github.com
add_site bbc.com
sleep 10   # let site icons download

# Restart so the grid redraws with icons, then focus the first tile.
adb shell am force-stop $PKG
adb shell am start -n $PKG/.MainActivity
sleep 4
adb shell input keyevent KEYCODE_DPAD_DOWN KEYCODE_DPAD_UP
shot 03_home_with_apps 2

adb shell input keyevent KEYCODE_MENU
shot 04_tile_options 2
adb shell input keyevent KEYCODE_BACK
sleep 1
tap_text "Wikipedia"

# Wikipedia is opening; move the remote cursor a bit.
sleep 15
for i in 1 2 3 4 5 6; do adb shell input keyevent KEYCODE_DPAD_RIGHT KEYCODE_DPAD_DOWN; done
shot 05_website_with_cursor 2

adb shell input keyevent KEYCODE_MENU
shot 06_options_bar 2
adb shell input keyevent KEYCODE_BACK
sleep 1

# Scroll the page by pushing the cursor against the bottom edge.
for i in $(seq 1 30); do adb shell input keyevent KEYCODE_DPAD_DOWN; done
shot 07_scrolled 3

ls -la "$OUT"
