#!/usr/bin/env python3
"""Bounded UI smoke test for an already booted emulator and installed Manage Time APK.

This script never selects a device implicitly, installs an APK, starts an emulator,
or writes application data. It creates real usage events by opening Android Settings.
"""

from __future__ import annotations

import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET


PACKAGE = "com.managetime.app"
ACTIVITY = PACKAGE + "/.MainActivity"
DEVICE_XML = "/data/local/tmp/manage-time-smoke.xml"
TABS = [("今日", "01-today"), ("时间轴", "02-timeline"),
        ("统计", "03-statistics"), ("设置", "04-settings")]
PAGE_MARKERS = {"今日": "今日屏幕使用", "时间轴": "每一分钟，都有迹可循",
                "统计": "过去一周", "设置": "记录，由你掌控"}


class SmokeFailure(RuntimeError):
    pass


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


def label_center(xml: str, label: str) -> tuple[int, int]:
    """Use observed UIAutomator bounds, never guessed screen coordinates."""
    root = ET.fromstring(xml)
    matches = []
    for node in root.iter("node"):
        if node.get("package") != PACKAGE or node.get("text") != label:
            continue
        match = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds", ""))
        if match:
            left, top, right, bottom = map(int, match.groups())
            if right > left and bottom > top:
                matches.append(((left + right) // 2, (top + bottom) // 2))
    if not matches:
        raise SmokeFailure(f"Could not find a visible exact tab label {label!r}")
    # An app named 设置 can also appear in the usage list. Navigation is the lowest exact label.
    return max(matches, key=lambda point: point[1])


def crash_findings(log: str) -> dict[str, list[str]]:
    """Only report this app's fatal/ANR evidence, not unrelated emulator warnings."""
    lines = log.splitlines()
    crashes: list[str] = []
    anrs: list[str] = []
    own_process = re.compile(r"Process:\s*com\.managetime\.app(?:[:\w.]*)\s*[,\s]")
    for index, line in enumerate(lines):
        if "FATAL EXCEPTION" in line:
            block = lines[index:index + 100]
            # A different AndroidRuntime FATAL starts a new exception block.
            for offset, next_line in enumerate(block[1:], 1):
                if "FATAL EXCEPTION" in next_line:
                    block = block[:offset]
                    break
            if own_process.search("\n".join(block)):
                crashes.append("\n".join(block[:30]))
        if re.search(r">>>\s*com\.managetime\.app(?:[:\w.]*)\s*<<<", line):
            crashes.append(line)
        if "am_crash" in line and re.search(r"[,\s]com\.managetime\.app(?:[:,\s\]])", line):
            crashes.append(line)
        if re.search(r"\bANR in com\.managetime\.app(?:\b|:)", line):
            anrs.append(line)
        elif "am_anr" in line and re.search(r"[,\s]com\.managetime\.app(?:[:,\s\]])", line):
            anrs.append(line)
    return {"crashes": crashes, "anrs": anrs}


class EmulatorSmoke:
    def __init__(self, adb: str, serial: str, output: Path):
        self.adb = adb
        self.serial = serial
        self.output = output
        self.deadline = time.monotonic() + 240
        self.log_start: str | None = None
        self.report = {
            "schema_version": 1, "package": PACKAGE, "serial": serial,
            "started_at": utc_now(), "status": "running", "assertions": [],
            "captures": [], "crash_check": {"completed": False},
            "limitations": [
                "Emulator smoke only; not real-device or OEM background-reliability validation.",
                "No Bilibili/post recognition assertion; accessibility capture is not enabled.",
                "Does not prove historical usage accuracy or test every privacy control.",
            ],
        }

    def run_adb(self, *args: str, timeout: float = 20, final: bool = False) -> bytes:
        remaining = 15 if final else self.deadline - time.monotonic()
        if remaining <= 0:
            raise SmokeFailure("Smoke test exceeded the 240 second work budget")
        try:
            result = subprocess.run(
                [self.adb, "-s", self.serial, *args], stdout=subprocess.PIPE,
                stderr=subprocess.PIPE, timeout=min(timeout, remaining), check=False,
            )
        except (OSError, subprocess.TimeoutExpired) as error:
            raise SmokeFailure(f"adb {args[0] if args else ''} failed: {error}") from error
        if result.returncode:
            raise SmokeFailure(
                f"adb {' '.join(args)} exited {result.returncode}: "
                + result.stderr.decode("utf-8", errors="replace")[-2000:]
            )
        return result.stdout

    def text(self, *args: str, **kwargs) -> str:
        return self.run_adb(*args, **kwargs).decode("utf-8", errors="replace").strip()

    def passed(self, name: str, details: str) -> None:
        self.report["assertions"].append({"name": name, "status": "passed", "details": details})
        print(f"PASS {name}: {details}", flush=True)

    def pause(self, seconds: float) -> None:
        if time.monotonic() + seconds >= self.deadline:
            raise SmokeFailure("Smoke test time budget exhausted during polling")
        time.sleep(seconds)

    def dump_ui(self) -> str:
        errors = []
        for attempt in range(3):
            try:
                # Remove the prior dump so a failed command cannot return a stale hierarchy.
                self.run_adb("shell", "rm", "-f", DEVICE_XML, timeout=5)
                self.run_adb("shell", "uiautomator", "dump", DEVICE_XML, timeout=12)
                xml = self.text("exec-out", "cat", DEVICE_XML, timeout=5)
                tree = ET.fromstring(xml)
                if not any(node.get("package") == PACKAGE for node in tree.iter("node")):
                    raise SmokeFailure("Manage Time has no nodes in the current UI hierarchy")
                return xml
            except (SmokeFailure, ET.ParseError) as error:
                errors.append(str(error))
                if attempt < 2:
                    self.pause(0.8)
        raise SmokeFailure("UIAutomator dump failed after 3 attempts: " + " | ".join(errors))

    def execute(self) -> None:
        if self.text("get-state", timeout=8) != "device":
            raise SmokeFailure("The explicitly selected emulator is not online")
        qemu = [self.text("shell", "getprop", key, timeout=5)
                for key in ("ro.kernel.qemu", "ro.boot.qemu")]
        if "1" not in qemu:
            raise SmokeFailure("Device does not identify as a QEMU emulator; refusing appops changes")
        self.report["device"] = {
            "sdk": self.text("shell", "getprop", "ro.build.version.sdk", timeout=5),
            "model": self.text("shell", "getprop", "ro.product.model", timeout=5),
        }
        if not self.text("shell", "pm", "path", PACKAGE, timeout=8).startswith("package:"):
            raise SmokeFailure("Manage Time is not installed; install the APK before invoking this script")
        self.passed("explicit_emulator_and_installed_app", self.serial)
        self.run_adb("shell", "am", "force-stop", PACKAGE, timeout=8)
        self.log_start = self.text("shell", "date '+%m-%d %H:%M:%S.000'", timeout=5)
        if not re.fullmatch(r"\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.000", self.log_start):
            raise SmokeFailure("Could not establish device time for bounded logcat inspection")
        self.run_adb("shell", "appops", "set", PACKAGE, "GET_USAGE_STATS", "allow", timeout=8)
        operation = self.text("shell", "appops", "get", PACKAGE, "GET_USAGE_STATS", timeout=8)
        if "allow" not in operation:
            raise SmokeFailure("Emulator did not confirm usage access app-op")
        self.passed("usage_access", operation)

        self.run_adb("shell", "input", "keyevent", "KEYCODE_WAKEUP", timeout=5)
        self.run_adb("shell", "wm", "dismiss-keyguard", timeout=5)
        settings = self.text("shell", "am", "start", "-W", "-a", "android.settings.SETTINGS", timeout=20)
        if "Error:" in settings:
            raise SmokeFailure("Could not open Android Settings to create real usage events: " + settings)
        self.pause(3)
        started = self.text("shell", "am", "start", "-W", "-n", ACTIVITY, timeout=25)
        if "Error:" in started:
            raise SmokeFailure("Manage Time launch failed: " + started)
        self.pause(1.5)
        if not self.text("shell", "pidof", PACKAGE, timeout=5):
            raise SmokeFailure("Manage Time has no running process after launch")
        self.passed("launch_after_real_settings_use", "Opened system Settings for 3 seconds, then Manage Time")

        for label, stem in TABS:
            xml = self.dump_ui()
            x, y = label_center(xml, label)
            self.run_adb("shell", "input", "tap", str(x), str(y), timeout=5)
            self.pause(0.5)
            xml = self.dump_ui()
            texts = {node.get("text") for node in ET.fromstring(xml).iter("node")
                     if node.get("package") == PACKAGE}
            if PAGE_MARKERS[label] not in texts:
                raise SmokeFailure(f"Expected page marker {PAGE_MARKERS[label]!r} after tapping {label!r}")
            # All four bottom navigation labels must remain reachable after each navigation.
            for expected, _ in TABS:
                label_center(xml, expected)
            image = self.run_adb("exec-out", "screencap", "-p", timeout=10)
            if not image.startswith(b"\x89PNG\r\n\x1a\n"):
                raise SmokeFailure("screencap did not return a PNG")
            (self.output / f"{stem}.png").write_bytes(image)
            (self.output / f"{stem}.xml").write_text(xml, encoding="utf-8")
            self.report["captures"].append({"tab": label, "image": f"{stem}.png", "hierarchy": f"{stem}.xml"})
            self.passed(f"navigate_{stem}", f"Tapped observed {label} bounds at ({x}, {y}); captured app UI")

        if not self.text("shell", "pidof", PACKAGE, timeout=5):
            raise SmokeFailure("Manage Time process is absent at the end of navigation")
        self.passed("process_alive_after_navigation", PACKAGE)

    def inspect_logcat(self) -> None:
        if not self.log_start:
            return
        log = self.text("logcat", "-d", "-v", "threadtime", "-b", "main", "-b", "system",
                        "-b", "crash", "-b", "events", "-T", self.log_start, timeout=15, final=True)
        (self.output / "logcat.txt").write_text(log, encoding="utf-8")
        findings = crash_findings(log)
        self.report["crash_check"] = {"completed": True, "since_device_time": self.log_start, **findings}
        if findings["crashes"] or findings["anrs"]:
            raise SmokeFailure("Manage Time crash or ANR evidence was found in logcat")
        self.passed("no_app_crash_or_anr_in_logcat", "No matching fatal/native crash or ANR since test launch")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb", help="Path to Android platform-tools adb")
    parser.add_argument("--serial", default=os.environ.get("ANDROID_SERIAL"),
                        help="Explicit emulator serial (or ANDROID_SERIAL); physical devices are refused")
    parser.add_argument("--output", required=True, type=Path, help="Directory for report, XML, PNG and logcat artifacts")
    args = parser.parse_args()
    if not args.serial or not re.fullmatch(r"emulator-\d+", args.serial):
        parser.error("--serial or ANDROID_SERIAL must explicitly name emulator-<port>")
    args.output.mkdir(parents=True, exist_ok=True)
    smoke = EmulatorSmoke(args.adb, args.serial, args.output)
    errors = []
    try:
        smoke.execute()
    except (SmokeFailure, ET.ParseError, OSError) as error:
        errors.append(str(error))
    try:
        smoke.inspect_logcat()
    except (SmokeFailure, OSError) as error:
        errors.append(str(error))
    smoke.report["status"] = "failed" if errors else "passed"
    smoke.report["finished_at"] = utc_now()
    if errors:
        smoke.report["errors"] = errors
        for error in errors:
            print(f"FAIL {error}", file=sys.stderr)
    (args.output / "report.json").write_text(json.dumps(smoke.report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Report: {args.output / 'report.json'}", flush=True)
    return 1 if errors else 0


if __name__ == "__main__":
    raise SystemExit(main())
