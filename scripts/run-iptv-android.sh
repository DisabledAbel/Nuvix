#!/usr/bin/env bash
# Run only against an already running disposable emulator, with generated local media.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
mkdir -p work/iptv-device
rm -f work/iptv-device/iptv-reports/*.json
rm -f work/iptv-android.json work/iptv-android.md
PYTHONPATH=scripts python3 -m iptv.fixtures --root work/iptv-media --port 8765 > work/iptv-provider.log 2>&1 &
fixture_pid=$!
cleanup() { kill "$fixture_pid" 2>/dev/null || true; wait "$fixture_pid" 2>/dev/null || true; adb reverse --remove tcp:8765 >/dev/null 2>&1 || true; }
trap cleanup EXIT
PYTHONPATH=scripts python3 - <<'PY'
import time
from iptv.probe import Client, ProbeFailure
for attempt in range(100):
    try:
        Client(1, 'http://127.0.0.1:8765').get('http://127.0.0.1:8765/stats')
        break
    except ProbeFailure:
        time.sleep(.1)
else:
    raise SystemExit('Synthetic IPTV provider did not start')
PY
adb reverse tcp:8765 tcp:8765
test_status=0
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=app.opentv.iptv.IptvPlaybackTest \
  -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true \
  --stacktrace || test_status=$?
# Only explicitly structured test measurements cross into the uploaded artifact, never logcat.
if adb exec-out run-as com.nuvix.player.debug tar -C files -cf - iptv-reports > work/iptv-device.tar; then
  tar -xf work/iptv-device.tar -C work/iptv-device || test_status=1
fi
report_status=0
python3 scripts/test-iptv-playback.py --mode fixture --android-report-dir work/iptv-device/iptv-reports \
  --android-exit-code "$test_status" \
  --baseline work/iptv-baseline.json --output work/iptv-android.json || report_status=$?
if [[ "$test_status" != 0 || "$report_status" != 0 ]]; then exit 1; fi
