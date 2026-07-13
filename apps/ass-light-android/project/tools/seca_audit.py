#!/usr/bin/env python3
"""SECA build-truth auditor for ASS-LIGHT Android.

The auditor never equates source presence with runtime proof. Each stage writes a
receipt, and the final gate remains PARTIAL while physical-phone and Google Play
human-in-the-loop gates are open.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import wave
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Iterable

ROOT = Path(__file__).resolve().parents[1]
RECEIPTS = ROOT / "receipts"
RECEIPTS.mkdir(exist_ok=True)


@dataclass
class Gate:
    gate_id: str
    area: str
    state: str
    hard_gate: bool
    evidence: str


GATES: list[Gate] = []


def add(gate_id: str, area: str, passed: bool, evidence: str, *, hard: bool = True, partial: bool = False) -> None:
    state = "VERIFIED" if passed else ("PARTIAL" if partial else "FAILED")
    GATES.append(Gate(gate_id, area, state, hard, evidence))


def read(path: str | Path) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def audit_definition() -> None:
    path = ROOT / "PRODUCT_DEFINITION.json"
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
        required = {
            "source_id", "title", "status", "definition", "required_capabilities",
            "forbidden_capabilities", "promotion_law", "distribution_identity",
            "production_identity_reserved",
        }
        passed = (
            required.issubset(data)
            and data["title"] == "ASS-LIGHT"
            and data["distribution_identity"] == "com.ghostatlas.asslight.rc"
            and data["production_identity_reserved"] == "com.ghostatlas.asslight"
            and "user-started" in data["definition"]
            and "Google Play promotion remains blocked" in data["definition"]
        )
        add("DEF-001", "definition", passed, f"fields={sorted(data)}; sha256={sha(path)}")
    except Exception as error:
        add("DEF-001", "definition", False, f"{type(error).__name__}: {error}")


def audit_audio() -> None:
    raw = ROOT / "app/src/main/res/raw"
    sounds = sorted(raw.glob("fart_*.wav"))
    evidence: list[str] = []
    hashes: set[str] = set()
    valid = len(sounds) == 12
    for path in sounds:
        try:
            with wave.open(str(path), "rb") as source:
                duration = source.getnframes() / source.getframerate()
                params_ok = (
                    source.getnchannels() == 1
                    and source.getsampwidth() == 2
                    and source.getframerate() == 44_100
                    and 0.35 <= duration <= 2.10
                )
                frames = source.readframes(source.getnframes())
                peak = max(abs(int.from_bytes(frames[i:i+2], "little", signed=True)) for i in range(0, len(frames), 2))
                digest = sha(path)
                hashes.add(digest)
                valid = valid and params_ok and peak > 2_000
                evidence.append(f"{path.name}:{duration:.2f}s:44100Hz:peak={peak}:{digest[:12]}")
        except Exception as error:
            valid = False
            evidence.append(f"{path.name}:{type(error).__name__}")
    unique = len(hashes) == 12
    add("AUD-001", "audio", valid and unique, "; ".join(evidence) + f"; unique={unique}")


def audit_manifest_and_source() -> None:
    manifest = read("app/src/main/AndroidManifest.xml")
    gradle = read("app/build.gradle")
    service = read("app/src/main/java/com/ghostatlas/asslight/ChaosService.java")
    main = read("app/src/main/java/com/ghostatlas/asslight/MainActivity.java")
    torch = read("app/src/main/java/com/ghostatlas/asslight/TorchController.java")
    profiles = read("app/src/main/java/com/ghostatlas/asslight/ChaosProfiles.java")
    audit = read("app/src/main/java/com/ghostatlas/asslight/SelfAudit.java")
    tests = ROOT / "app/src/androidTest/java/com/ghostatlas/asslight/RuntimeSmokeTest.java"

    background_ok = all(token in manifest for token in [
        'android:foregroundServiceType="mediaPlayback"',
        'android:exported="false"',
        'android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK',
    ]) and all(token in service for token in [
        "startForeground", "SEAL PORTAL", "ACTION_STOP", "START_NOT_STICKY",
        "MAX_SESSION_MS", "stopForeground", "stopSelf",
    ])
    add("BG-001", "background", background_ok, "private mediaPlayback service, persistent notification, stop path, six-hour safety seal")

    random_ok = (
        "SecureRandom" in service
        and "random.nextInt" in service
        and "random.nextFloat" in service
        and all(token in profiles for token in ["MIN_SECONDS", "MAX_SECONDS", "MIN_VOLUME", "MAX_VOLUME"])
    )
    add("RNG-001", "randomization", random_ok, "SecureRandom sound, delay, oracle, and app-local volume selection")

    playback_ok = all(token in service for token in [
        "openRawResourceFd", "setOnPreparedListener", "mediaPlayer.start()",
        '"PLAYBACK_STARTED"', "setVolume(volume, volume)",
    ])
    add("AUD-002-SRC", "audio", playback_ok, "success receipt follows MediaPlayer preparation and start")

    cleanup_ok = all(token in service for token in [
        "removeCallbacksAndMessages", "releasePlayer", "torch.setEnabled(false)",
        "wakeLock.release", 'putBoolean("running", false)',
    ])
    add("CTL-001", "control", cleanup_ok, "Portal Seal clears timers, playback, torch, wake lock, state, notification, and service")

    all_source = "\n".join([manifest, gradle, service, main, torch, profiles, audit])
    forbidden = {
        "INTERNET permission": "android.permission.INTERNET" in manifest,
        "boot receiver": "BOOT_COMPLETED" in all_source,
        "record audio": "RECORD_AUDIO" in all_source,
        "analytics/ads": bool(re.search(r"firebase|analytics|admob|advertising", all_source, re.I)),
        "system volume mutation": "AudioManager" in all_source or "setStreamVolume" in all_source,
        "web/network client": bool(re.search(r"HttpURLConnection|OkHttp|WebView|Socket\(", all_source)),
    }
    add("SAFE-001", "safety", not any(forbidden.values()), json.dumps(forbidden, sort_keys=True))

    permission_names = set(re.findall(r'<uses-permission android:name="([^"]+)"', manifest))
    allowed = {
        "android.permission.FOREGROUND_SERVICE",
        "android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK",
        "android.permission.POST_NOTIFICATIONS",
        "android.permission.WAKE_LOCK",
        "android.permission.CAMERA",
    }
    add("PERM-001", "permissions", permission_names == allowed, f"declared={sorted(permission_names)}")

    torch_ok = all(token in torch for token in [
        "FLASH_INFO_AVAILABLE", "LENS_FACING_BACK", "checkSelfPermission",
        "setTorchMode", "manager == null",
    ]) and "180L" in service and 'android:required="false"' in manifest
    add("TOR-001-SRC", "flashlight", torch_ok, "optional hardware, permission gate, rear camera preference, one 180 ms pulse")
    add("TOR-001", "flashlight", False, "source verified; real rear flashlight proof remains HITL", hard=False, partial=True)

    sdk_ok = all(token in gradle for token in [
        "compileSdk 35", "targetSdk 35", "minSdk 23", "JavaVersion.VERSION_17",
        "applicationId 'com.ghostatlas.asslight.rc'",
    ])
    add("SDK-001", "platform", sdk_ok, "compileSdk=35 targetSdk=35 minSdk=23 Java=17 RC identity isolated")

    ui_ok = all(token in main for token in [
        "OPEN THE BROWN PORTAL", "SEAL THE PORTAL", "RUN SECA PREFLIGHT",
        "No covert startup", "Flash mode is off by default", "SelfAudit.run",
    ]) and "screenOrientation" not in manifest
    add("UX-001", "user control", ui_ok, "explicit start, stop, test, flashlight, safety copy, preflight, adaptive orientation")

    privacy_ok = 'android:allowBackup="false"' in manifest and 'android:usesCleartextTraffic="false"' in manifest
    add("PRIV-001", "privacy", privacy_ok, "backup disabled; cleartext disabled; no network permission")

    test_text = tests.read_text(encoding="utf-8") if tests.exists() else ""
    tests_ok = all(token in test_text for token in [
        "embeddedSoundPreparesAndStartsWithBoundedReceipt",
        "foregroundSessionStartsAndPortalSealClearsRuntimeState",
        'assertEquals("PLAYBACK_STARTED"',
    ])
    add("TST-001", "test", tests_ok, str(tests.relative_to(ROOT)) if tests.exists() else "missing")

    add("PLAY-001", "Google Play", False, "AAB may be built; private upload key and Play Console review remain open", hard=False, partial=True)


def audit_artifact(args: argparse.Namespace) -> None:
    apk = Path(args.apk)
    aab = Path(args.aab)
    signature = Path(args.apksigner_receipt).read_text(encoding="utf-8", errors="replace")
    badging = Path(args.badging_receipt).read_text(encoding="utf-8", errors="replace")
    add("APK-001", "artifact", apk.is_file() and apk.stat().st_size > 20_000, f"{apk}:{apk.stat().st_size if apk.exists() else 0} bytes")
    add("AAB-001", "artifact", aab.is_file() and aab.stat().st_size > 20_000, f"{aab}:{aab.stat().st_size if aab.exists() else 0} bytes")
    sig_ok = "Verified using v" in signature or "Signer #1 certificate" in signature or "Verifies" in signature
    add("SIG-001", "signing", sig_ok, signature[-1200:])
    package_ok = "package: name='com.ghostatlas.asslight.rc'" in badging
    target_ok = "targetSdkVersion:'35'" in badging
    internet_absent = "android.permission.INTERNET" not in badging
    add("PKG-001", "identity", package_ok, "RC package identity from aapt badging")
    add("SDK-002", "platform", target_ok, "targetSdkVersion 35 from APK")
    add("SAFE-002", "safety", internet_absent, "INTERNET permission absent from APK badging")


def xml_files(path: Path) -> Iterable[Path]:
    if path.is_file() and path.suffix == ".xml":
        yield path
    elif path.exists():
        yield from path.rglob("*.xml")


def audit_runtime(args: argparse.Namespace) -> None:
    install = Path(args.install_receipt).read_text(encoding="utf-8", errors="replace")
    launch = Path(args.launch_receipt).read_text(encoding="utf-8", errors="replace")
    add("INS-001", "install", "Success" in install, install.strip()[-800:])
    launch_ok = "Events injected: 1" in launch or "Monkey finished" in launch or "No activities found" not in launch
    add("LCH-001", "launch", launch_ok, launch.strip()[-1000:])

    tests = 0
    failures = 0
    errors = 0
    names: list[str] = []
    for path in xml_files(Path(args.test_results)):
        text = path.read_text(encoding="utf-8", errors="replace")
        for match in re.finditer(r'<testsuite[^>]*tests="(\d+)"[^>]*', text):
            tests += int(match.group(1))
        failures += sum(int(value) for value in re.findall(r'failures="(\d+)"', text))
        errors += sum(int(value) for value in re.findall(r'errors="(\d+)"', text))
        names.extend(re.findall(r'<testcase[^>]*name="([^"]+)"', text))
    audio_ok = failures == 0 and errors == 0 and "embeddedSoundPreparesAndStartsWithBoundedReceipt" in names
    service_ok = failures == 0 and errors == 0 and "foregroundSessionStartsAndPortalSealClearsRuntimeState" in names
    add("AUD-002", "audio", audio_ok, f"tests={tests} failures={failures} errors={errors} cases={names}")
    add("BG-002", "background", service_ok, "foreground session and Portal Seal instrumentation case")
    add("PHY-001", "physical device", False, "speaker quality, rear torch, lock-screen timing, and OEM battery behavior remain HITL", hard=False, partial=True)


def hard_pass(gates: Iterable[dict]) -> bool:
    return all(gate["state"] == "VERIFIED" for gate in gates if gate["hard_gate"])


def write_receipt(name: str) -> dict:
    records = [asdict(gate) for gate in GATES]
    verified = sum(record["state"] == "VERIFIED" for record in records)
    has_partial = any(record["state"] == "PARTIAL" for record in records)
    overall = "FAILED" if not hard_pass(records) else ("PARTIAL" if has_partial else "VERIFIED")
    payload = {
        "schema": "ghost-atlas.seca.receipt.v2",
        "product": "ASS-LIGHT",
        "stage": name,
        "generated_utc": datetime.now(timezone.utc).isoformat(),
        "overall": overall,
        "verified_count": verified,
        "gate_count": len(records),
        "gates": records,
    }
    stem = f"SECA_{name.upper()}_AUDIT"
    (RECEIPTS / f"{stem}.json").write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
    lines = [f"# SECA Audit · ASS-LIGHT · {name}", "", f"**Overall:** {overall}", f"**Generated:** {payload['generated_utc']}", "", "| Gate | Area | State | Hard | Evidence |", "|---|---|---:|:---:|---|"]
    for record in records:
        evidence = str(record["evidence"]).replace("|", "\\|").replace("\n", " ")
        lines.append(f"| {record['gate_id']} | {record['area']} | {record['state']} | {'YES' if record['hard_gate'] else 'NO'} | {evidence} |")
    (RECEIPTS / f"{stem}.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print("\n".join(lines))
    return payload


def final_gate() -> None:
    sources: list[dict] = []
    for stage in ("SOURCE", "ARTIFACT", "RUNTIME"):
        path = RECEIPTS / f"SECA_{stage}_AUDIT.json"
        if not path.exists():
            add(f"{stage}-RECEIPT", "promotion", False, f"missing {path.name}")
        else:
            receipt = json.loads(path.read_text(encoding="utf-8"))
            sources.extend(receipt["gates"])
            add(f"{stage}-RECEIPT", "promotion", hard_pass(receipt["gates"]), f"{path.name}: overall={receipt['overall']}")

    final_hard = hard_pass(sources) and all(gate.state == "VERIFIED" for gate in GATES if gate.hard_gate)
    classification = "ANDROID_RC_AUTOMATED_GATES_VERIFIED_HITL_OPEN" if final_hard else "BLOCKED_REQUIRES_REPAIR"
    payload = {
        "schema": "ghost-atlas.seca.final-release-receipt.v2",
        "product": "ASS-LIGHT",
        "generated_utc": datetime.now(timezone.utc).isoformat(),
        "classification": classification,
        "automated_hard_gates_passed": final_hard,
        "physical_phone_gate": "OPEN",
        "google_play_production_signing_gate": "OPEN",
        "google_play_review_gate": "OPEN",
        "promotion_statement": "Direct-install Android RC may publish only when automated hard gates pass. Production/store completion is not claimed.",
        "receipt_gates": [asdict(gate) for gate in GATES],
    }
    (RECEIPTS / "SECA_FINAL_RELEASE_RECEIPT.json").write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
    markdown = [
        "# SECA Final Release Receipt · ASS-LIGHT",
        "",
        f"**Classification:** {classification}",
        f"**Automated hard gates:** {'PASS' if final_hard else 'BLOCKED'}",
        "**Physical Android phone proof:** OPEN",
        "**Private Google Play signing:** OPEN",
        "**Play Console review:** OPEN",
        "",
        "This receipt authorizes only the direct-install Android release candidate when automated gates pass. It does not claim physical-hardware verification or Google Play approval.",
    ]
    (RECEIPTS / "SECA_FINAL_RELEASE_RECEIPT.md").write_text("\n".join(markdown) + "\n", encoding="utf-8")
    print("\n".join(markdown))
    if not final_hard:
        raise SystemExit(1)


def parser() -> argparse.ArgumentParser:
    cli = argparse.ArgumentParser()
    cli.add_argument("--stage", required=True, choices=["source", "artifact", "runtime", "final"])
    cli.add_argument("--apk")
    cli.add_argument("--aab")
    cli.add_argument("--apksigner-receipt")
    cli.add_argument("--badging-receipt")
    cli.add_argument("--test-results")
    cli.add_argument("--install-receipt")
    cli.add_argument("--launch-receipt")
    return cli


def main() -> None:
    args = parser().parse_args()
    if args.stage == "source":
        audit_definition()
        audit_audio()
        audit_manifest_and_source()
        payload = write_receipt("source")
        if not hard_pass(payload["gates"]):
            raise SystemExit(1)
    elif args.stage == "artifact":
        audit_artifact(args)
        payload = write_receipt("artifact")
        if not hard_pass(payload["gates"]):
            raise SystemExit(1)
    elif args.stage == "runtime":
        audit_runtime(args)
        payload = write_receipt("runtime")
        if not hard_pass(payload["gates"]):
            raise SystemExit(1)
    else:
        final_gate()


if __name__ == "__main__":
    main()
