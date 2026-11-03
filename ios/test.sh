#!/usr/bin/env bash
# Runs the iOS unit tests on the first available iPhone simulator.
set -euo pipefail
cd "$(dirname "$0")"
xcodegen generate
DEVICE=$(xcrun simctl list devices available -j | python3 -c '
import json, sys
devices = json.load(sys.stdin)["devices"]
for runtime in sorted(devices, reverse=True):
    if "iOS" in runtime:
        for d in devices[runtime]:
            if d["name"].startswith("iPhone"):
                print(d["udid"]); sys.exit()
')
echo "Testing on simulator $DEVICE"
xcodebuild -project Target.xcodeproj -scheme Target -destination "id=$DEVICE" -derivedDataPath build-test \
  CODE_SIGNING_ALLOWED=NO test
