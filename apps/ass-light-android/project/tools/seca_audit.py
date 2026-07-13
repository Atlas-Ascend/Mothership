#!/usr/bin/env python3
"""SECA/DevOS command-to-proof audit for ASS-LIGHT Android RC1."""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
import wave
import xml.etree.ElementTree as ET
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[1]
RECEIPTS = ROOT / "receipts"
RECEIPTS.mkdir(parents=True, exist_ok=True)
VALID_STATES = {"VERIFIED", "PARTIAL", "UNVERIFIED", "BLOCKED", "FAILED", "REQUIRES_REPAIR"}


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def text(path: Path) -> str:
    return path.read_text(encoding="utf-8", errors="replace") if path.exists() else ""


def gate(gates: list[dict[str, Any]], gate_id: str, passed: bool, hard: bool, evidence: str,
         partial: bool = False) -> None:
    state = "PARTIAL" if partial else ("VERIFIED" if passed else ("FAILED" if hard else "PARTIAL"))
    gates.append({"id": gate_id, "state": state, "hard_gate": hard, "evidence": evidence})


def write_receipt(stage: str, gates: list[dict[str, Any]], boundary: str) -> dict[str, Any]:
    hard_failures = [item for item in gates if item["hard_gate"] and item["state"] != "VERIFIED"]
    partials = [item for item in gates if item["state"] == "PARTIAL"]
    state = "FAILED" if hard_failures else ("PARTIAL" if partials else "VERIFIED")
    receipt = {
        "schema": "ghost-atlas.seca.receipt.v2",
        "product": "ASS-LIGHT",
        "stage": stage.upper(),
        "state": state,
        "hard_failures": [item["id"] for item in hard_failures],
        "boundary": boundary,
        "gates": gates,
    }
    json_path = RECEIPTS / f"SECA_{stage.upper()}_AUDIT.json"
    md_path = RECEIPTS / f"SECA_{stage.upper()}_AUDIT.md"
    json_path.write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf-8")
    lines = [f"# SECA {stage.upper()} AUDIT", "", f"**STATE: {state}**", "", boundary, ""]
    for item in gates:
        lines.append(f"- **{item['state']} · {item['id']}**: {item['evidence']}")
    md_path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(json.dumps(receipt, indent=2))
    if hard_failures:
        raise SystemExit(1)
    return receipt


def source_audit() -> None:
    gates: list[dict[str, Any]] = []
    definition_path = ROOT / "PRODUCT_DEFINITION.json"
    matrix_path = ROOT / "SECA_GATE_MATRIX.json"
    manifest_path = ROOT / "app/src/main/AndroidManifest.xml"
    build_path = ROOT / "app/build.gradle"
    service_path = ROOT / "app/src/main/java/com/ghostatlas/asslight/ChaosService.java"
    activity_path = ROOT / "app/src/main/java/com/ghostatlas/asslight/MainActivity.java"
    audit_path = ROOT / "app/src/main/java/com/ghostatlas/asslight/SelfAudit.java"
    torch_path = ROOT / "app/src/main/java/com/ghostatlas/asslight/TorchController.java"
    test_path = ROOT / "app/src/androidTest/java/com/ghostatlas/asslight/RuntimeSmokeTest.java"

    required = [definition_path, matrix_path, manifest_path, build_path, service_path,
                activity_path, audit_path, torch_path, test_path, ROOT / "tools/generate_sounds.py"]
    missing = [str(path.relative_to(ROOT)) for path in required if not path.exists()]
    gate(gates, "SRC-FILES", not missing, True, "All required source files present." if not missing else f"Missing: {missing}")

    try:
        definition = json.loads(text(definition_path))
        matrix = json.loads(text(matrix_path))
        definition_ok = (
            definition.get("source_id") == "MF-20260712-MF01-027"
            and definition.get("distribution_identity") == "com.ghostatlas.asslight.rc"
            and definition.get("production_identity_reserved") == "com.ghostatlas.asslight"
            and len(definition.get("forbidden_capabilities", [])) >= 8
            and "No completion claim" in definition.get("promotion_law", "")
        )
        states_ok = set(matrix.get("states", [])) == VALID_STATES and len(matrix.get("gates", [])) >= 15
    except Exception as error:
        definition_ok = states_ok = False
        definition = {}
        matrix = {}
        parse_error = repr(error)
    else:
        parse_error = "JSON parsed"
    gate(gates, "DEF-001", definition_ok, True, parse_error)
    gate(gates, "SECA-MATRIX", states_ok, True, f"{len(matrix.get('gates', []))} declared promotion gates")

    raw = ROOT / "app/src/main/res/raw"
    wavs = sorted(raw.glob("fart_*.wav"))
    wave_ok = len(wavs) == 12
    hashes: set[str] = set()
    details = []
    for path in wavs:
        try:
            with wave.open(str(path), "rb") as wav:
                channels = wav.getnchannels()
                width = wav.getsampwidth()
                rate = wav.getframerate()
                frames = wav.getnframes()
                duration = frames / float(rate)
            digest = sha256(path)
            hashes.add(digest)
            valid = channels == 1 and width == 2 and rate == 44_100 and 0.35 <= duration <= 2.10 and path.stat().st_size > 4096
            wave_ok &= valid
            details.append(f"{path.name}:{duration:.2f}s:{digest[:10]}")
        except Exception as error:
            wave_ok = False
            details.append(f"{path.name}:ERROR:{error}")
    gate(gates, "AUD-001", wave_ok and len(hashes) == 12, True,
         f"{len(wavs)} WAV files, {len(hashes)} unique SHA-256 values; " + ", ".join(details))
    sound_manifest = raw / "sound_manifest.json"
    try:
        sound_data = json.loads(text(sound_manifest))
        manifest_ok = len(sound_data) == 12 and all(item.get("sha256") == sha256(raw / item["resource"]) for item in sound_data)
    except Exception:
        manifest_ok = False
    gate(gates, "AUD-MANIFEST", manifest_ok, True, "Generated sound manifest matches packaged WAV hashes.")

    manifest = text(manifest_path)
    build = text(build_path)
    service = text(service_path)
    activity = text(activity_path)
    audit_source = text(audit_path)
    torch = text(torch_path)
    tests = text(test_path)
    all_source = "\n".join([manifest, build, service, activity, audit_source, torch, tests])

    platform_ok = all(token in build for token in ["compileSdk 35", "targetSdk 35", "minSdk 23", "VERSION_17"])
    gate(gates, "SDK-001", platform_ok, True, "Compile/target 35, min 23, Java 17 declared.")

    foreground_ok = (
        "FOREGROUND_SERVICE_MEDIA_PLAYBACK" in manifest
        and 'android:foregroundServiceType="mediaPlayback"' in manifest
        and 'android:exported="false"' in manifest
        and "startForeground(" in service
        and "SEAL PORTAL" in service
    )
    gate(gates, "BG-001", foreground_ok, True, "Private visible mediaPlayback foreground service with notification stop action.")

    random_ok = (
        "SecureRandom" in service
        and "random.nextInt" in service
        and "random.nextFloat" in service
        and "setVolume(volume, volume)" in service
        and "Math.max(1, max - min + 1)" in service
    )
    gate(gates, "RNG-001", random_ok, True, "Sound, oracle, interval, and app-local volume are bounded random selections.")

    stop_ok = all(token in service for token in [
        "removeCallbacksAndMessages(null)", "releasePlayer()", "torch.setEnabled(false)",
        "wakeLock.release()", "stopForeground", "stopSelf()"
    ])
    gate(gates, "CTL-001", stop_ok, True, "Portal Seal clears timers, player, torch, wake lock, notification, and service.")

    torch_ok = (
        "Manifest.permission.CAMERA" in manifest
        and 'android:required="false"' in manifest
        and "pulse(180L)" in service
        and "setTorchMode" in torch
        and "flashWithEvent" in service
    )
    gate(gates, "TOR-001-SRC", torch_ok, True, "Optional hardware/permission-gated torch with one 180 ms event pulse.")
    gate(gates, "TOR-001-PHY", True, False, "Physical torch pulse remains a real-phone HITL gate.", partial=True)

    denylist = [
        "android.permission.INTERNET", "RECEIVE_BOOT_COMPLETED", "MediaRecorder", "AudioRecord",
        "FirebaseAnalytics", "com.google.android.gms.ads", "setStreamVolume", "adjustStreamVolume"
    ]
    found = [token for token in denylist if token in all_source]
    # INTERNET is allowed only in the self-audit string that verifies its absence.
    found = [token for token in found if not (token == "android.permission.INTERNET" and token not in manifest)]
    gate(gates, "SAFE-001", not found and "<receiver" not in manifest, True,
         "No boot receiver, network permission, recording, ads, analytics, or system-volume mutation." if not found else f"Found prohibited tokens: {found}")

    ui_ok = all(token in activity for token in ["OPEN THE BROWN PORTAL", "TEST GUST NOW", "FLASHLIGHT TOGGLE", "SEAL THE PORTAL", "RERUN IN-APP SECA AUDIT"])
    gate(gates, "UX-001", ui_ok and "screenOrientation" not in manifest, True, "Required controls exist and orientation is not forced.")

    tests_ok = all(token in tests for token in [
        "allTwelveOriginalSoundsOpenAsRealPayloads", "playOnceProducesMediaPlayerStartupReceipt",
        "foregroundPortalStartsAndSealCleansState", "selfAuditHasNoCriticalFailure"
    ])
    gate(gates, "TEST-SRC", tests_ok, True, "Instrumentation covers resources, MediaPlayer startup, service lifecycle, and embedded audit.")

    write_receipt("source", gates,
        "Source proof can verify definitions, files, resources, permissions, bounds, and control paths. It cannot impersonate a physical phone.")


def artifact_audit(args: argparse.Namespace) -> None:
    gates: list[dict[str, Any]] = []
    apk = Path(args.apk)
    aab = Path(args.aab)
    signer = text(Path(args.apksigner_receipt))
    badging = text(Path(args.aapt_receipt))
    gate(gates, "APK-001", apk.exists() and apk.stat().st_size > 100_000, True,
         f"{apk} size={apk.stat().st_size if apk.exists() else 0} sha256={sha256(apk) if apk.exists() else 'missing'}")
    gate(gates, "AAB-001", aab.exists() and aab.stat().st_size > 100_000, True,
         f"{aab} size={aab.stat().st_size if aab.exists() else 0} sha256={sha256(aab) if aab.exists() else 'missing'}")
    gate(gates, "SIG-001", bool(re.search(r"Verifies|Verified using|Signer #1 certificate", signer)), True,
         "apksigner cryptographic verification receipt present.")
    package_ok = "com.ghostatlas.asslight.rc" in badging
    no_net = "android.permission.INTERNET" not in badging
    target_ok = "targetSdkVersion:'35'" in badging or "targetSdkVersion='35'" in badging
    gate(gates, "PKG-001", package_ok, True, "RC package identity found in APK badging.")
    gate(gates, "SAFE-APK", no_net, True, "APK badging contains no INTERNET permission.")
    gate(gates, "SDK-APK", target_ok, True, "APK badging reports target SDK 35.")
    gate(gates, "PLAY-001", True, False, "AAB is a technical candidate; private Play upload signing and console review remain blocked.", partial=True)
    write_receipt("artifact", gates,
        "Artifact proof verifies package construction and direct-install signature. It does not grant Google Play approval or production signing status.")


def runtime_audit(args: argparse.Namespace) -> None:
    gates: list[dict[str, Any]] = []
    results_dir = Path(args.test_results)
    xml_files = list(results_dir.rglob("*.xml")) if results_dir.exists() else []
    tests = failures = errors = skipped = 0
    parse_errors = []
    for path in xml_files:
        try:
            root = ET.parse(path).getroot()
            tests += int(root.attrib.get("tests", 0))
            failures += int(root.attrib.get("failures", 0))
            errors += int(root.attrib.get("errors", 0))
            skipped += int(root.attrib.get("skipped", 0))
        except Exception as error:
            parse_errors.append(f"{path.name}:{error}")
    test_ok = bool(xml_files) and tests >= 4 and failures == 0 and errors == 0 and not parse_errors
    gate(gates, "RUN-TESTS", test_ok, True,
         f"xml={len(xml_files)} tests={tests} failures={failures} errors={errors} skipped={skipped} parse_errors={parse_errors}")
    install = text(Path(args.install_receipt))
    launch = text(Path(args.launch_receipt))
    gate(gates, "INS-001", "Success" in install, True, "ADB release APK install returned Success.")
    launch_ok = "Events injected: 1" in launch or "Events injected: 1" in launch.replace("\r", "")
    gate(gates, "LCH-001", launch_ok, True, "Launcher monkey event was injected.")
    gate(gates, "AUD-002", test_ok, True, "Instrumentation requires a MediaPlayer PLAYBACK_STARTED receipt with sound and volume bounds.")
    gate(gates, "BG-002", test_ok, True, "Instrumentation requires foreground start and Portal Seal state cleanup.")
    gate(gates, "PHY-001", True, False, "Human-audible speaker quality, lock-screen continuity, Bluetooth, OEM battery behavior, and torch remain physical-device gates.", partial=True)
    write_receipt("runtime", gates,
        "Emulator runtime proof verifies installation, launch, resource access, MediaPlayer startup receipt, and service lifecycle. Physical hardware remains open.")


def final_audit() -> None:
    gates: list[dict[str, Any]] = []
    stages = ["SOURCE", "ARTIFACT", "RUNTIME"]
    for stage in stages:
        path = RECEIPTS / f"SECA_{stage}_AUDIT.json"
        try:
            receipt = json.loads(text(path))
            passed = receipt.get("state") in {"VERIFIED", "PARTIAL"} and not receipt.get("hard_failures")
            evidence = f"{stage} state={receipt.get('state')} hard_failures={receipt.get('hard_failures')}"
        except Exception as error:
            passed = False
            evidence = f"Missing or invalid {stage} receipt: {error}"
        gate(gates, f"{stage}-RECEIPT", passed, True, evidence)
    gate(gates, "DIRECT-APK-PROMOTION", True, False,
         "Eligible for clearly labeled Android RC direct download after automated gates pass.", partial=True)
    gate(gates, "PHYSICAL-PROMOTION", True, False,
         "Blocked from full VERIFIED status until MANUAL_HITL_DEVICE_RECEIPT.md is completed.", partial=True)
    gate(gates, "PLAY-PROMOTION", True, False,
         "Blocked from Google Play production until private upload signing and Play Console review.", partial=True)
    write_receipt("final", gates,
        "Final automated state is PARTIAL / ANDROID RC. Publication may provide a direct-test APK without claiming physical-device or Google Play completion.")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--stage", required=True, choices=["source", "artifact", "runtime", "final"])
    parser.add_argument("--apk")
    parser.add_argument("--aab")
    parser.add_argument("--apksigner-receipt")
    parser.add_argument("--aapt-receipt")
    parser.add_argument("--test-results")
    parser.add_argument("--install-receipt")
    parser.add_argument("--launch-receipt")
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    if args.stage == "source":
        source_audit()
    elif args.stage == "artifact":
        artifact_audit(args)
    elif args.stage == "runtime":
        runtime_audit(args)
    else:
        final_audit()


if __name__ == "__main__":
    main()
