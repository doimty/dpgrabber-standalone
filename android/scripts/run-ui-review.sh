#!/usr/bin/env bash
set -Eeuo pipefail

# 只允许此工作流的新鲜 AOSP 模拟器，不允许连接实体设备或本机 AVD。
[[ "${GITHUB_ACTIONS:-}" == true && "${RUNNER_OS:-}" == Linux ]] || {
  printf '%s\n' '拒绝执行：仅允许一次性 GitHub Linux runner。' >&2
  exit 1
}
PROFILE=${1:-}
case "$PROFILE" in
  compact-light) NIGHT=no; FONT_SCALE=1.0 ;;
  compact-dark-large) NIGHT=yes; FONT_SCALE=1.3 ;;
  *) printf '%s\n' '未知的离线验收 profile。' >&2; exit 1 ;;
esac
ANDROID_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
REPORT="$ANDROID_DIR/app/build/reports/ui-review/$PROFILE"
mkdir -p "$REPORT"
TARGET_PACKAGE=com.doimty.dpgrabber
TEST_PACKAGE=com.doimty.dpgrabber.test
RUNNER="$TEST_PACKAGE/androidx.test.runner.AndroidJUnitRunner"
SERIAL="emulator-${EMULATOR_PORT:-5554}"
ADB=(adb -s "$SERIAL")
DEVICE_OUTPUT="/sdcard/Android/data/$TARGET_PACKAGE/files/ui-review"
ISOLATION_MARKER=/data/local/tmp/dpgrabber-ui-firewall.txt
ISOLATED=false
PULLED=false
STAGE=emulator-preflight
TARGET_UID=
TEST_UID=

# 不保存原始 logcat/instrumentation 日志；只输出脱敏文本。
sanitize() {
  python3 -u -c '
import re, sys
for line in sys.stdin:
    line = re.sub(r"(?i)(authorization\s*[:=]\s*)(?:bearer\s+)?\S+", r"\1[REDACTED]", line)
    line = re.sub(r"(?i)((?:password|passwd|token|secret|cookie|phone|mobile)\s*[\"\x27]?\s*[:=]\s*[\"\x27]?)[^\s,;\"\x27}]+", r"\1[REDACTED]", line)
    line = re.sub(r"(?<!\d)1[3-9]\d{9}(?!\d)", "[PHONE]", line)
    line = re.sub(r"[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}", "[EMAIL]", line)
    line = re.sub(r"https?://[^\s\"<>]+", "[URL]", line)
    sys.stdout.write(line)
'
}

finish() {
  local result=$?
  trap - EXIT
  set +e
  if [[ "$ISOLATED" == true ]]; then
    for table in iptables ip6tables; do
      timeout 20s "${ADB[@]}" shell "$table -w 5 -nvx -L OUTPUT" > "$REPORT/$table-after.txt" 2>&1
    done
    if [[ "$PULLED" != true ]]; then
      timeout 45s "${ADB[@]}" pull "$DEVICE_OUTPUT" "$REPORT/device" 2>&1 | sanitize > "$REPORT/artifact-pull.txt"
    fi
    timeout 20s "${ADB[@]}" logcat -d --uid="$TARGET_UID" -v threadtime 2>&1 | sanitize > "$REPORT/logcat-app-redacted.txt"
    if (( result != 0 )); then
      # 仅捕获失败时屏幕的真实状态；不将其冒充已通过的测试截图。
      timeout 20s "${ADB[@]}" exec-out screencap -p > "$REPORT/failure-current-screen.png"
      timeout 25s "${ADB[@]}" shell uiautomator dump /sdcard/ui-review-failure.xml 2>&1 | sanitize > "$REPORT/failure-hierarchy-dump.txt"
      timeout 15s "${ADB[@]}" pull /sdcard/ui-review-failure.xml "$REPORT/failure-current-hierarchy.xml" 2>&1 | sanitize > "$REPORT/failure-hierarchy-pull.txt"
    fi
    timeout 10s "${ADB[@]}" shell am force-stop "$TARGET_PACKAGE" >/dev/null 2>&1
    # 防火墙保留到模拟器销毁，不在失败路径中开放真实网络。
  fi
  REPORT="$REPORT" RESULT="$result" PROFILE="$PROFILE" ISOLATED="$ISOLATED" STAGE="$STAGE" python3 - <<'PY'
import json, os, pathlib, xml.etree.ElementTree as ET
path = pathlib.Path(os.environ['REPORT'])
result = int(os.environ['RESULT'])
(path / 'run-status.json').write_text(json.dumps({
    'profile': os.environ['PROFILE'], 'exit_code': result,
    'network_isolation_verified': os.environ['ISOLATED'] == 'true',
    'last_stage': os.environ['STAGE'],
    'state': 'passed' if result == 0 else 'failed; inspect JUnit and actual device artifacts'
}, indent=2) + '\n')
if not (path / 'instrumentation.xml').exists():
    suite = ET.Element('testsuite', name='AndroidOfflineUiReview', tests='1', failures='1')
    case = ET.SubElement(suite, 'testcase', name='isolation_and_instrumentation_setup')
    ET.SubElement(case, 'failure', message='未完成隔离或未得到 instrumentation 结果').text = '请检查工作流步骤及此目录内的状态记录。'
    ET.ElementTree(suite).write(path / 'instrumentation.xml', encoding='utf-8', xml_declaration=True)
elif result != 0:
    tree = ET.parse(path / 'instrumentation.xml')
    suite = tree.getroot()
    if not suite.findall('.//failure'):
        case = ET.SubElement(suite, 'testcase', name='device_artifact_validation')
        ET.SubElement(case, 'failure', message='instrumentation 后实际产物验证失败').text = '测试进程结果不能代替真实截图证据。'
        suite.set('tests', str(int(suite.get('tests', '1')) + 1))
        suite.set('failures', '1')
        tree.write(path / 'instrumentation.xml', encoding='utf-8', xml_declaration=True)
PY
  if (( result != 0 )); then
    printf '%s\n' "离线界面验收失败（stage=$STAGE, exit=$result）；已尽力保存现有证据，未伪造截图或通过结论。" >&2
  fi
  exit "$result"
}
trap finish EXIT

[[ "$("${ADB[@]}" shell getprop ro.kernel.qemu | tr -d '\r')" == 1 ]]
[[ "$("${ADB[@]}" shell getprop ro.build.version.sdk | tr -d '\r')" == 34 ]]
"${ADB[@]}" root
# root 可能重启 adbd；限时等待，禁止在设备不可用时继续。
timeout 60s "${ADB[@]}" wait-for-device
[[ "$("${ADB[@]}" shell id -u | tr -d '\r')" == 0 ]] || {
  printf '%s\n' '隔离失败：AOSP adbd 未获得 UID 0。' >&2; exit 1
}
for package in "$TARGET_PACKAGE" "$TEST_PACKAGE"; do
  [[ -z "$("${ADB[@]}" shell pm path "$package" | tr -d '\r')" ]] || {
    printf '%s\n' '拒绝执行：模拟器已安装目标包，不是新鲜验收环境。' >&2; exit 1
  }
done
STAGE=install-apks
"${ADB[@]}" install "$ANDROID_DIR/app/build/outputs/apk/debug/app-debug.apk"
"${ADB[@]}" install "$ANDROID_DIR/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"

# 安装不启动 Activity。先从 PackageManager 精确读取单个包的 UID，再隔离两族网络。
# 任何歧义（包名不精确匹配、多 UID、系统 UID）都必须中止，不猜、不回退到免隔离。
package_uid() {
  local package=$1 raw uid
  raw=$("${ADB[@]}" shell cmd package list packages -U --user 0 "$package" | tr -d '\r')
  printf '%s\n' "$raw" | tee "$REPORT/package-${package}-uid.txt" >&2
  uid=$(printf '%s\n' "$raw" | python3 -c '
import re, sys
package = sys.argv[1]
uids = set()
for line in sys.stdin:
    match = re.match(r"^\s*package:(\S+)\s+uid:(\d+)\s*$", line)
    if match and match.group(1) == package:
        uids.add(int(match.group(2)))
if len(uids) != 1:
    sys.exit("无法唯一确认 " + package + " 的 UID；拒绝运行测试（诊断已保存）。")
uid = uids.pop()
if uid < 10000:
    sys.exit("目标 UID 不是普通应用；拒绝添加防火墙规则。")
print(uid)
' "$package")
  [[ -n "$uid" ]] || { printf '%s\n' "缺少 $package 的 UID" >&2; exit 1; }
  [[ -n "$("${ADB[@]}" shell pm path "$package" | tr -d '\r')" ]] || {
    printf '%s\n' "拒绝执行：$package 未安装或包路径不可读。" >&2; exit 1
  }
  printf '%s\n' "$uid"
}
STAGE=resolve-package-uids
TARGET_UID=$(package_uid "$TARGET_PACKAGE")
TEST_UID=$(package_uid "$TEST_PACKAGE")
for table in iptables ip6tables; do
  STAGE="isolate-$table"
  printf '%s\n' "校验 $table owner UID 隔离（失败将直接终止，不运行登录）。"
  "${ADB[@]}" shell "command -v $table" >/dev/null
  "${ADB[@]}" shell "$table -w 5 -S OUTPUT" >/dev/null
  for uid in $(printf '%s\n%s\n' "$TARGET_UID" "$TEST_UID" | sort -u); do
    "${ADB[@]}" shell "$table -w 5 -I OUTPUT 1 -m owner --uid-owner $uid -j REJECT"
    "${ADB[@]}" shell "$table -w 5 -C OUTPUT -m owner --uid-owner $uid -j REJECT"
  done
  "${ADB[@]}" shell "$table -w 5 -S OUTPUT" > "$REPORT/$table-rules.txt"
  "${ADB[@]}" shell "$table -w 5 -nvx -L OUTPUT" > "$REPORT/$table-before.txt"
done
MARKER=$(printf 'version=1\ntargetUid=%s\ntestUid=%s\nipv4=blocked\nipv6=blocked' "$TARGET_UID" "$TEST_UID")
printf '%s\n' "$MARKER" | "${ADB[@]}" shell "cat > $ISOLATION_MARKER"
"${ADB[@]}" shell chmod 644 "$ISOLATION_MARKER"
[[ "$("${ADB[@]}" shell cat "$ISOLATION_MARKER" | tr -d '\r')" == "$MARKER" ]]
printf '%s\n' "$MARKER" > "$REPORT/network-isolation.txt"
ISOLATED=true

STAGE=configure-profile
"${ADB[@]}" shell svc wifi disable
"${ADB[@]}" shell svc data disable
"${ADB[@]}" shell wm size 720x1440
"${ADB[@]}" shell wm density 320
"${ADB[@]}" shell settings put system font_scale "$FONT_SCALE"
"${ADB[@]}" shell cmd uimode night "$NIGHT"
"${ADB[@]}" shell settings put secure show_ime_with_hard_keyboard 1
"${ADB[@]}" shell input keyevent KEYCODE_WAKEUP
"${ADB[@]}" shell wm dismiss-keyguard
{
  printf 'profile=%s\nfont_scale=%s\nnight=%s\nserial=%s\n' "$PROFILE" "$FONT_SCALE" "$NIGHT" "$SERIAL"
  "${ADB[@]}" shell wm size
  "${ADB[@]}" shell wm density
  "${ADB[@]}" shell settings get system font_scale
  "${ADB[@]}" shell cmd uimode night
  "${ADB[@]}" shell getprop ro.build.fingerprint
} > "$REPORT/device-metadata.txt"
"${ADB[@]}" logcat -c

# am instrument 的 shell exit=0 不代表测试通过；保留管道状态并检查 runner 协议。
STAGE=instrumentation
set +e
timeout --signal=TERM --kill-after=15s 600s "${ADB[@]}" shell am instrument -w -r \
  -e class com.doimty.dpgrabber.UiReviewInstrumentedTest,com.doimty.dpgrabber.SessionLifecycleInstrumentedTest -e profile "$PROFILE" "$RUNNER" 2>&1 | sanitize | tee "$REPORT/instrumentation-redacted.txt"
PIPE_RESULTS=("${PIPESTATUS[@]}")
set -e
REPORT="$REPORT" PROFILE="$PROFILE" AM_RESULT="${PIPE_RESULTS[0]}" FILTER_RESULT="${PIPE_RESULTS[1]}" TEE_RESULT="${PIPE_RESULTS[2]}" python3 - <<'PY'
import os, pathlib, re, sys, xml.etree.ElementTree as ET
path = pathlib.Path(os.environ['REPORT'])
text = (path / 'instrumentation-redacted.txt').read_text()
codes = re.findall(r'^INSTRUMENTATION_CODE:\s*(-?\d+)\s*$', text, re.M)
status = re.findall(r'^INSTRUMENTATION_STATUS_CODE:\s*(-?\d+)\s*$', text, re.M)
passed = status.count('0')
summary = re.findall(r'\bOK \((\d+) tests?\)', text)
failed = (
    any(os.environ[key] != '0' for key in ('AM_RESULT', 'FILTER_RESULT', 'TEE_RESULT'))
    or codes != ['-1'] or passed == 0 or not summary or int(summary[-1]) != passed
    or any(int(code) < 0 for code in status)
    or bool(re.search(r'FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_ABORTED|Process crashed|shortMsg=', text))
)
suite = ET.Element('testsuite', name='AndroidOfflineUiReview', tests='1', failures='1' if failed else '0')
properties = ET.SubElement(suite, 'properties')
ET.SubElement(properties, 'property', name='instrumentation_passed_count', value=str(passed))
case = ET.SubElement(suite, 'testcase', name='instrumentation_protocol', classname=os.environ.get('PROFILE', 'offline'))
if failed:
    ET.SubElement(case, 'failure', message='instrumentation 未得到完整、无失败的最终结果').text = text
ET.SubElement(suite, 'system-out').text = text
ET.ElementTree(suite).write(path / 'instrumentation.xml', encoding='utf-8', xml_declaration=True)
if failed:
    sys.exit('离线 instrumentation 失败或超时；请检查脱敏输出及真实设备产物。')
PY

# 无真实截图、布局树或 metadata 即失败，不能仅以 instrumentation 的 OK 作为视觉证据。
STAGE=validate-device-artifacts
"${ADB[@]}" pull "$DEVICE_OUTPUT" "$REPORT/device" 2>&1 | sanitize > "$REPORT/artifact-pull.txt"
PULLED=true
REPORT="$REPORT" python3 - <<'PY'
import json, os, pathlib, struct, sys, xml.etree.ElementTree as ET
path = pathlib.Path(os.environ['REPORT']) / 'device'
for pattern, label in [('*.png', 'PNG 截图'), ('*.xml', 'XML 布局树'), ('*.json', 'JSON metadata')]:
    files = [p for p in path.rglob(pattern) if p.is_file() and p.stat().st_size > 0]
    if not files:
        sys.exit('缺少实际设备产物：' + label)
    for file in files:
        if file.suffix == '.png':
            header = file.read_bytes()[:24]
            if len(header) != 24 or header[:8] != b'\x89PNG\r\n\x1a\n' or min(struct.unpack('>II', header[16:24])) == 0:
                sys.exit('设备 PNG 为空或格式错误：' + file.name)
        elif file.suffix == '.xml':
            ET.parse(file)
        else:
            json.loads(file.read_text())
PY
STAGE=completed
printf '%s\n' '离线 instrumentation 与实际产物检查通过；视觉结论仍需逐屏评审。'
