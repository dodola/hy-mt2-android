#!/usr/bin/env bash
# Sweeps threadpool strict/poll settings via debug.hymt.* props; prints load_ms and pp/tg per combo.
M=${1:-/data/user/0/com.hymt2.app/files/hymt2-1.8b-stq1_0.gguf}
for cfg in "1 50" "0 50" "1 0" "0 0"; do
  read -r strict poll <<<"$cfg"
  adb shell setprop debug.hymt.strict "$strict"; adb shell setprop debug.hymt.poll "$poll"
  adb shell am force-stop com.hymt2.app; adb logcat -c
  adb shell am start -n com.hymt2.app/.bench.BenchActivity --es model "$M" --ei pp 64 --ei tg 16 >/dev/null
  for _ in $(seq 1 45); do sleep 2; adb logcat -d -s HYMT_BENCH | grep -qE "RESULT|failed" && break; done
  echo "strict=$strict poll=$poll: $(adb logcat -d -s HYMT_BENCH | grep -E 'load_ms|RESULT' | sed 's/.*HYMT_BENCH: //' | tr '\n' ' ')"
done
