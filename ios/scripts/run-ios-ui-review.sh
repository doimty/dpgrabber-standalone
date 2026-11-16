#!/usr/bin/env bash
#
# DPGrabber iOS - offline UI review screenshots on the iOS Simulator.
#
# Usage:
#   run-ios-ui-review.sh <output-dir> [runtime-prefix]
#
#   <output-dir>        directory that receives the PNGs, logs and summary.md
#   [runtime-prefix]    optional iOS runtime version prefix (for example "18" or
#                       "26.2"). When omitted the script enumerates the runtimes
#                       that are really installed, picks the newest iOS 26.x as
#                       the primary round, and additionally runs the newest
#                       iOS 18.x runtime when one exists (skipped, with a written
#                       reason, when it does not).
#
# Contract with the app (ContentView, DEBUG builds only):
#   -uiReviewTab 0 | 1 | 2 | 3   -> 工作台 | 规则 | 记录 | 我的
#   -uiReviewSettings YES        -> 高级设置（真实页面，未登录）
#   -uiReviewWaitSection YES     -> 程序化定位等待微调区域（不代表手势/保存验收）
#   The launch arguments are only honoured when the app is built with the Debug
#   configuration (SWIFT_ACTIVE_COMPILATION_CONDITIONS=DEBUG), so this script
#   must be pointed at a Debug simulator build.
#
# Safety:
#   - refuses to run outside GitHub Actions (GITHUB_ACTIONS != true)
#   - refuses to run when credential environment variables are present
#   - never logs in, never calls the real backend: it only launches the four
#     tabs of the offline UI and takes screenshots
#
# Outputs (per round, <runtime> looks like "iOS-26.2"):
#   <runtime>-<appearance>-tab<N>.png        screenshot
#   <runtime>-<appearance>-tab<N>-launch.log  simctl launch output
#   <runtime>-<appearance>-app.log           desensitized device log excerpt
#   runtimes.txt, screenshots.tsv, summary.md
#
# Environment overrides:
#   IOS_BUNDLE_ID            default com.doimty.dpgrabber
#   IOS_SIM_APP              default ios/build/Debug-iphonesimulator/DPGrabber.app
#   UI_REVIEW_SETTLE_SECONDS default 8 (settle time before each screenshot)
#
set -Eeuo pipefail

# With `set -e` a failing command aborts silently. Print the exact line and
# command so a red run always explains itself in the log.
trap 'printf "::error::ui-review failed at line %s: %s\n" "$LINENO" "$BASH_COMMAND" >&2' ERR

BUNDLE_ID="${IOS_BUNDLE_ID:-com.doimty.dpgrabber}"
APP_PATH="${IOS_SIM_APP:-ios/build/Debug-iphonesimulator/DPGrabber.app}"
SETTLE_SECONDS="${UI_REVIEW_SETTLE_SECONDS:-8}"
TABS="0 1 2 3 settings settings-wait"
CREATED_UDIDS=""
FAILURES=0
LAUNCH_FAILURES=0

log() { printf '[ui-review] %s\n' "$*"; }
fail() { printf '::error::%s\n' "$*" >&2; exit 1; }

cleanup() {
  local udid
  for udid in $CREATED_UDIDS; do
    xcrun simctl shutdown "$udid" >/dev/null 2>&1 || true
    xcrun simctl delete "$udid" >/dev/null 2>&1 || true
  done
}
trap 'result=$?; trap - EXIT; cleanup; exit "$result"' EXIT

# ---------------------------------------------------------------- preconditions

if [ "${GITHUB_ACTIONS:-}" != "true" ]; then
  printf 'refusing to run outside GitHub Actions (GITHUB_ACTIONS must be true)\n' >&2
  exit 2
fi

if [ "$#" -lt 1 ] || [ "$#" -gt 2 ]; then
  printf 'usage: %s <output-dir> [runtime-prefix]\n' "$0" >&2
  exit 2
fi

# The UI review must stay offline: no real credentials may be present.
for name in DPGRABBER_PHONE DPGRABBER_PASSWORD DPGRABBER_TOKEN DPGRABBER_ACCOUNT; do
  eval "value=\${$name:-}"
  if [ -n "$value" ]; then
    fail "credential environment variable $name must not be set for the offline UI review"
  fi
done

OUT="$1"
RUNTIME_FILTER="${2:-}"
mkdir -p "$OUT"

[ -d "$APP_PATH" ] || fail "simulator app bundle not found: $APP_PATH (build the Debug configuration first)"
[ -f "$APP_PATH/Info.plist" ] || fail "app bundle has no Info.plist: $APP_PATH"

XCODE_VERSION="$(xcodebuild -version 2>/dev/null | head -n 1 || true)"
[ -n "$XCODE_VERSION" ] || XCODE_VERSION="unknown"

log "bundle id: $BUNDLE_ID"
log "app bundle: $APP_PATH"
log "host: $(uname -m) / $(sw_vers -productVersion 2>/dev/null || echo unknown)"
log "xcode: $XCODE_VERSION"

# ------------------------------------------------------------ runtime discovery

list_ios_runtimes() {
  xcrun simctl list runtimes -j | python3 -c '
import json, sys

data = json.load(sys.stdin)
rows = []
for runtime in data.get("runtimes", []):
    name = runtime.get("name", "")
    identifier = runtime.get("identifier", "")
    if not runtime.get("isAvailable", False):
        continue
    if not name.startswith("iOS "):
        continue
    version = runtime.get("version", "") or name[4:]
    key = []
    for part in version.split("."):
        try:
            key.append(int(part))
        except ValueError:
            key.append(0)
    rows.append((tuple(key), name, version, identifier))

rows.sort(key=lambda row: row[0], reverse=True)
for _key, name, version, identifier in rows:
    print("%s\t%s\t%s" % (name, version, identifier))
'
}

RUNTIME_LINES="$(list_ios_runtimes)"
[ -n "$RUNTIME_LINES" ] || fail "no available iOS simulator runtime reported by 'xcrun simctl list runtimes'"
printf '%s\n' "$RUNTIME_LINES" > "$OUT/runtimes.txt"
log "available iOS runtimes:"
printf '%s\n' "$RUNTIME_LINES" | while IFS="$(printf '\t')" read -r name version identifier; do
  log "  $name ($version)"
done

# Prints the newest runtime whose version starts with the given prefix.
pick_runtime() {
  printf '%s\n' "$RUNTIME_LINES" | awk -F'\t' -v prefix="$1" 'index($2, prefix) == 1 { print; exit }'
}

PRIMARY=""
LEGACY=""
LEGACY_NOTE=""
if [ -n "$RUNTIME_FILTER" ]; then
  PRIMARY="$(pick_runtime "$RUNTIME_FILTER")"
  if [ -z "$PRIMARY" ]; then
    fail "requested iOS runtime prefix '$RUNTIME_FILTER' is not installed; available: $(printf '%s' "$RUNTIME_LINES" | cut -f1-2 | tr '\n' ' ')"
  fi
  LEGACY_NOTE="已显式指定 runtime 前缀 ${RUNTIME_FILTER}，因此只跑该轮次，未追加旧系统轮次。"
else
  PRIMARY="$(pick_runtime "26.")"
  if [ -z "$PRIMARY" ]; then
    fail "no installed iOS 26.x runtime; the Xcode 26 SDK review must run on iOS 26.x"
  fi
  LEGACY="$(pick_runtime "18.")"
  if [ -n "$LEGACY" ]; then
    LEGACY_NOTE="已追加 iOS 18.x 旧系统轮次。"
  else
    LEGACY_NOTE="本次 runner 未安装可用的 iOS 18.x runtime，按规则跳过旧系统轮次（未伪造截图）。"
    log "skip legacy round: no iOS 18.x runtime installed"
  fi
fi

# -------------------------------------------------------------- device helpers

pick_device_type() {
  xcrun simctl list devicetypes -j | python3 -c '
import json, re, sys

data = json.load(sys.stdin)
best = None
fallback = None
for device_type in data.get("devicetypes", []):
    name = device_type.get("name", "")
    identifier = device_type.get("identifier", "")
    if not name.startswith("iPhone"):
        continue
    if fallback is None:
        fallback = (name, identifier)
    match = re.search(r"iPhone (\d+)", name)
    if not match:
        continue
    # Prefer the newest iPhone generation, then the regular "Pro" size
    # (not Pro Max, not SE/Plus/air variants) as a stable reference device.
    score = (int(match.group(1)), 1 if "Pro" in name else 0, 0 if "Max" in name else 1)
    if best is None or score > best[0]:
        best = (score, name, identifier)

if best is not None:
    print("%s\t%s" % (best[1], best[2]))
elif fallback is not None:
    print("%s\t%s" % fallback)
'
}

assert_png() {
  # Usage: assert_png <file>; echoes "WxH" on success, returns 1 on failure.
  local file="$1" size magic dimensions
  if [ ! -f "$file" ]; then
    printf 'missing screenshot: %s\n' "$file" >&2
    return 1
  fi
  size="$(wc -c < "$file" | tr -d ' ')"
  if [ "$size" -le 0 ]; then
    printf 'screenshot is empty (0 bytes): %s\n' "$file" >&2
    return 1
  fi
  magic="$(od -An -tx1 -N8 "$file" | tr -d ' \n')"
  if [ "$magic" != "89504e470d0a1a0a" ]; then
    printf 'screenshot is not a PNG (magic=%s): %s\n' "$magic" "$file" >&2
    return 1
  fi
  dimensions="$(python3 -c '
import struct, sys

with open(sys.argv[1], "rb") as handle:
    header = handle.read(24)
width, height = struct.unpack(">II", header[16:24])
print("%dx%d" % (width, height))
' "$file")"
  case "$dimensions" in
    *x0|0x*) printf 'screenshot has zero dimension (%s): %s\n' "$dimensions" "$file" >&2; return 1 ;;
  esac
  printf '%s' "$dimensions"
  return 0
}

# Desensitize device logs: phone numbers, passwords and tokens are masked and
# lines are truncated, so no real credential can end up in the artifact.
collect_logs() {
  local udid="$1" key="$2" appearance="$3" raw target
  raw="$OUT/${key}-${appearance}-app.raw.log"
  target="$OUT/${key}-${appearance}-app.log"
  if ! xcrun simctl spawn "$udid" log show --last 2m --style compact \
      --predicate 'process == "DPGrabber"' > "$raw" 2>/dev/null; then
    printf 'log collection failed for %s (%s)\n' "$key" "$appearance" > "$target"
    rm -f "$raw"
    return 0
  fi
  sed -E \
    -e 's/1[3-9][0-9]{9}/<phone>/g' \
    -e 's/([Pp]ass(word|wd)?|[Pp]wd)([[:space:]]*[=:][[:space:]]*)[^[:space:],;"]+/\1\3<redacted>/g' \
    -e 's/([Tt]oken|[Ss]ecret|[Aa]uthorization|[Bb]earer)([[:space:]]*[=:][[:space:]]*|[[:space:]]+)[A-Za-z0-9._~+\/=-]{8,}/\1\2<redacted>/g' \
    "$raw" | awk '{ print substr($0, 1, 400) }' > "$target"
  rm -f "$raw"
}

# ------------------------------------------------------------------ run a round

TSV="$OUT/screenshots.tsv"
: > "$TSV"

run_round() {
  local runtime_name="$1" runtime_version="$2" runtime_id="$3"
  local runtime_key device_type device_name udid appearance tab
  runtime_key="$(printf '%s' "$runtime_name" | tr ' ' '-')"

  device_type="$(pick_device_type)"
  [ -n "$device_type" ] || fail "no iPhone simulator device type available"
  if [ -z "$(printf '%s' "$device_type" | cut -f2)" ]; then
    fail "could not resolve an iPhone simulator device type identifier"
  fi
  device_name="DPGrabberReview-${runtime_key}-$$"

  log "=== round $runtime_name ($runtime_version) ==="
  # The default device (newest iPhone) does not exist on older runtimes, so try
  # the preferred type first and fall back to types that exist in most runtimes.
  udid=""
  local candidate
  for candidate in "$(printf '%s' "$device_type" | cut -f2)" \
                   "com.apple.CoreSimulator.SimDeviceType.iPhone-16" \
                   "com.apple.CoreSimulator.SimDeviceType.iPhone-15" \
                   "com.apple.CoreSimulator.SimDeviceType.iPhone-SE-3rd-generation"; do
    if xcrun simctl create "$device_name" "$candidate" "$runtime_id" \
        > "$OUT/${runtime_key}-create.txt" 2> "$OUT/${runtime_key}-create-error.txt"; then
      udid="$(tr -d '[:space:]' < "$OUT/${runtime_key}-create.txt")"
      if [ -n "$udid" ]; then
        log "device type: $candidate"
        break
      fi
    fi
    log "device type unavailable for $runtime_name, trying next: $candidate"
    udid=""
  done
  [ -n "$udid" ] || fail "simctl create failed for $runtime_name with every known iPhone device type"
  CREATED_UDIDS="$CREATED_UDIDS $udid"
  log "device: $device_name ($udid) / type: $(printf '%s' "$device_type" | cut -f1)"

  # Freshly created device: this is the cold boot of the round.
  xcrun simctl boot "$udid"
  xcrun simctl bootstatus "$udid" -b
  # Deterministic status bar so screenshot diffs are comparable.
  xcrun simctl status_bar "$udid" override --time "9:41" --batteryState charged \
    --batteryLevel 100 --cellularBars 4 --wifiBars 3 >/dev/null 2>&1 || true

  xcrun simctl install "$udid" "$APP_PATH"
  log "installed app once on $udid"
  local data_container ready_marker
  data_container="$(xcrun simctl get_app_container "$udid" "$BUNDLE_ID" data)"
  ready_marker="$data_container/tmp/ui-review-ready"

  for appearance in light dark; do
    xcrun simctl ui "$udid" appearance "$appearance" >/dev/null 2>&1 \
      || log "warning: could not switch appearance to $appearance"
    sleep 2
    for tab in $TABS; do
      local shot status bytes dimensions launch_rc=0 initial_tab="$tab" settings_review=NO wait_review=NO
      if [ "$tab" = settings ] || [ "$tab" = settings-wait ]; then initial_tab=3; settings_review=YES; fi
      if [ "$tab" = settings-wait ]; then wait_review=YES; fi
      shot="$OUT/${runtime_key}-${appearance}-tab${tab}.png"
      xcrun simctl terminate "$udid" "$BUNDLE_ID" >/dev/null 2>&1 || true
      rm -f "$ready_marker"
      xcrun simctl launch "$udid" "$BUNDLE_ID" \
        -AppleLanguages "(zh-Hans)" -AppleLocale zh_CN \
        -uiReviewCapture YES -uiReviewTab "$initial_tab" -uiReviewSettings "$settings_review" -uiReviewWaitSection "$wait_review" \
        > "$OUT/${runtime_key}-${appearance}-tab${tab}-launch.log" 2>&1 || launch_rc=$?
      if [ "$launch_rc" -ne 0 ]; then
        LAUNCH_FAILURES=$((LAUNCH_FAILURES + 1))
        FAILURES=$((FAILURES + 1))
        log "launch exited $launch_rc for $appearance tab$tab; launch log follows"
        head -c 2000 "$OUT/${runtime_key}-${appearance}-tab${tab}-launch.log" 2>/dev/null \
          | sed -E 's/[[:digit:]]{11}/<phone>/g' >&2 || true
      fi
      local ready_attempt=0
      while [ ! -s "$ready_marker" ] && [ "$ready_attempt" -lt 45 ] && [ "$launch_rc" -eq 0 ]; do
        sleep 1
        ready_attempt=$((ready_attempt + 1))
      done
      if [ ! -s "$ready_marker" ]; then
        log "application did not report foreground content readiness for $appearance tab$tab"
        FAILURES=$((FAILURES + 1))
      fi
      sleep "$SETTLE_SECONDS"
      xcrun simctl io "$udid" screenshot --type=png "$shot" >/dev/null 2>&1 || true

      if [ "$launch_rc" -eq 0 ] && [ -s "$ready_marker" ] && dimensions="$(assert_png "$shot")"; then
        status="ok"
      else
        status="invalid"
        dimensions="-"
        FAILURES=$((FAILURES + 1))
      fi
      if [ -f "$shot" ]; then bytes="$(wc -c < "$shot" | tr -d ' ')"; else bytes=0; fi
      printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
        "$runtime_name" "$runtime_key" "$appearance" "$tab" "$(basename "$shot")" \
        "$bytes" "$dimensions" "$status" >> "$TSV"
      log "  $appearance tab$tab -> $(basename "$shot") ($bytes bytes, $dimensions, $status)"
    done

    # Contract guard: the four tabs must not render identically. If they do,
    # the DEBUG-only -uiReviewTab hook is probably not wired up and the
    # screenshots would be meaningless evidence.
    local invalid_count unique_hashes
    invalid_count="$(awk -F'\t' -v k="$runtime_key" -v a="$appearance" \
      '$2 == k && $3 == a && $8 != "ok"' "$TSV" | wc -l | tr -d ' ')"
    if [ "$invalid_count" -eq 0 ]; then
      unique_hashes="$(shasum -a 256 "$OUT/${runtime_key}-${appearance}"-tab*.png \
        | awk '{ print $1 }' | sort -u | wc -l | tr -d ' ')"
      if [ "$unique_hashes" -eq 1 ] && [ "$LAUNCH_FAILURES" -gt 0 ]; then
        printf '::error::%s %s: identical screenshots and %s failed launch(es) - the app never came to the foreground, see *-launch.log\n' \
          "$runtime_key" "$appearance" "$LAUNCH_FAILURES" >&2
        FAILURES=$((FAILURES + 1))
      elif [ "$unique_hashes" -eq 1 ]; then
        printf '::error::%s %s: all four tab screenshots are identical, -uiReviewTab (DEBUG only) is likely not applied\n' \
          "$runtime_key" "$appearance" >&2
        FAILURES=$((FAILURES + 1))
      fi
    fi

    collect_logs "$udid" "$runtime_key" "$appearance"
  done
}

run_round "$(printf '%s' "$PRIMARY" | cut -f1)" "$(printf '%s' "$PRIMARY" | cut -f2)" "$(printf '%s' "$PRIMARY" | cut -f3)"
if [ -n "$LEGACY" ]; then
  run_round "$(printf '%s' "$LEGACY" | cut -f1)" "$(printf '%s' "$LEGACY" | cut -f2)" "$(printf '%s' "$LEGACY" | cut -f3)"
fi

# -------------------------------------------------------------------- summary

if [ -n "$LEGACY" ]; then
  ROUNDS=2
else
  ROUNDS=1
fi
EXPECTED=$(( ROUNDS * 2 * 6 ))
ACTUAL="$(wc -l < "$TSV" | tr -d ' ')"
SUMMARY="$OUT/summary.md"

{
  echo "# iOS UI review screenshots"
  echo
  echo "- 生成时间（UTC）：$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
  echo "- Runner 镜像：${ImageOS:-unknown} ${ImageVersion:-unknown}（$(uname -m)）"
  echo "- Xcode：$XCODE_VERSION"
  echo "- Bundle ID：\`$BUNDLE_ID\`（Debug 模拟器构建）"
  echo "- 启动参数契约：\`-uiReviewTab 0/1/2/3\` → 工作台 / 规则 / 记录 / 我的；\`-uiReviewSettings YES\` 打开高级设置，\`-uiReviewWaitSection YES\` 定位新增等待设置，仅 DEBUG 生效"
  echo "- 语言/区域：\`-AppleLanguages (zh-Hans)\`、\`-AppleLocale zh_CN\`"
  echo "- 主轮次 runtime：$(printf '%s' "$PRIMARY" | cut -f1)（$(printf '%s' "$PRIMARY" | cut -f2)）"
  if [ -n "$LEGACY" ]; then
    echo "- 旧系统轮次 runtime：$(printf '%s' "$LEGACY" | cut -f1)（$(printf '%s' "$LEGACY" | cut -f2)）"
  fi
  echo "- 旧系统说明：$LEGACY_NOTE"
  echo "- 截图数量：${ACTUAL}（期望 ${EXPECTED}）"
  echo
  echo "## 已枚举的可用 iOS runtime"
  echo
  echo '| runtime | version | identifier |'
  echo '| --- | --- | --- |'
  while IFS="$(printf '\t')" read -r name version identifier; do
    [ -n "$name" ] || continue
    echo "| $name | $version | \`$identifier\` |"
  done < "$OUT/runtimes.txt"
  echo
  echo "## 截图清单"
  echo
  echo '| runtime | appearance | tab | file | bytes | pixels | status |'
  echo '| --- | --- | --- | --- | --- | --- | --- |'
  while IFS="$(printf '\t')" read -r runtime_name runtime_key appearance tab file bytes dimensions status; do
    [ -n "$file" ] || continue
    echo "| $runtime_name | $appearance | $tab | \`$file\` | $bytes | $dimensions | $status |"
  done < "$TSV"
  echo
  echo "## 日志"
  echo
  echo "每轮的 \`*-app.log\` 为 \`simctl spawn <udid> log show --last 2m --predicate 'process == \"DPGrabber\"'\` 的脱敏摘录（手机号/密码/token 已掩码，单行截断 400 字符）。"
  echo "脚本不登录、不访问真实业务接口；App 未登录状态下即可渲染四个页面。"
  echo
  echo "## 未覆盖"
  echo
  echo "- 真实点击与手势：设置等待区由 DEBUG 参数程序化定位，并非真实滚动或保存回归；需要 XCUITest 或真机进一步覆盖。"
  echo "- 真机、签名与推送：模拟器采用临时 ad-hoc 签名，设备 IPA 未签名；本流程不能证明真机安装或业务验收。"
} > "$SUMMARY"

if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
  cat "$SUMMARY" >> "$GITHUB_STEP_SUMMARY" || true
fi

log "summary written to $SUMMARY"

if [ "$ACTUAL" -ne "$EXPECTED" ]; then
  fail "expected $EXPECTED screenshots, found $ACTUAL rows in screenshots.tsv"
fi

if [ "$FAILURES" -ne 0 ]; then
  fail "$FAILURES screenshot validation failure(s) (missing/empty/non-PNG/identical tabs); see $SUMMARY"
fi

log "all screenshots captured and validated"
