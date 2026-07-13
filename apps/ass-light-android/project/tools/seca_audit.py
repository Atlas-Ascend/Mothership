#!/usr/bin/env python3
"""SECA build-truth auditor for ASS-LIGHT Android RC1.

Stages are deliberately separate: source truth, compiled artifact truth, emulator
runtime truth, and final promotion classification. A later stage never upgrades an
earlier missing receipt by assumption.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
import wave
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Iterable

ROOT = Path(__file__).resolve().parents[1]
RECEIPTS = ROOT / "receipts"
RECEIPTS.mkdir(parents=True, exist_ok=True)


@dataclass
class Gate:
    id: str
    area: str
    state: str
    hard: bool
    evidence: str


GATES: list[Gate] = []


def add(gate_id: str, area: str, passed: bool, evidence: str, *, hard: bool = True, partial: bool = False) -> None:
    state = "VERIFIED" if passed and not partial else "PARTIAL" if passed and partial else "BLOCKED" if hard else "UNVERIFIED"
    GATES.append(Gate(gate_id, area, state, hard, evidence))


def read(relative: str) -> str:
    return (ROOT / relative).read_text(encoding="utf-8")


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def overall(gates: Iterable[Gate]) -> str:
    items = list(gates)
    if any(g.hard and g.state != "VERIFIED" for g in items):
        return "BLOCKED"
    if any(g.state != "VERIFIED" for g in items):
        return "PARTIAL"
    return "VERIFIED"


def write_receipt(stage: str) -> str:
    state = overall(GATES)
    stamp = datetime.now(timezone.utc).isoformat()
    payload = {
        "schema": "ghost-atlas.seca.receipt.v2",
        "product": "ASS-LIGHT",
        "stage": stage,
        "overall": state,
        "generated_at": stamp,
        "gates": [asdict(g) for g in GATES],
    }
    base = "SECA_FINAL_RELEASE_RECEIPT" if stage == "final" else f"SECA_{stage.upper()}_AUDIT"
    (RECEIPTS / f"{base}.json").write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
    rows = [
        f"# SECA Audit · ASS-LIGHT · {stage}", "", f"**Overall:** {state}", f"**Generated:** {stamp}", "",
        "| Gate | Area | State | Hard | Evidence |", "|---|---|---:|:---:|---|",
    ]
    for gate in GATES:
        evidence = gate.evidence.replace("|", "\\|").replace("\n", " ")
        rows.append(f"| {gate.id} | {gate.area} | {gate.state} | {'YES' if gate.hard else 'NO'} | {evidence} |")
    (RECEIPTS / f"{base}.md").write_text("\n".join(rows) + "\n", encoding="utf-8")
    print("\n".join(rows))
    return state


def audit_source() -> None:
    definition = json.loads(read("PRODUCT_DEFINITION.json"))
    required = {
        "source_id", "title", "status", "definition", "required_capabilities", "forbidden_capabilities",
        "promotion_law", "distribution_identity", "production_identity_reserved",
    }
    definition_ok = (
        required.issubset(definition)
        and definition["title"] == "ASS-LIGHT"
        and definition["distribution_identity"] == "com.ghostatlas.asslight.rc"
        and definition["production_identity_reserved"] == "com.ghostatlas.asslight"
        and "user-started" in definition["definition"]
        and "Google Play promotion remains blocked" in definition["definition"]
    )
    add("DEF-001", "definition", definition_ok, f"fields={sorted(definition.keys())}; sha256={sha256(ROOT / 'PRODUCT_DEFINITION.json')}")

    raw = ROOT / "app/src/main/res/raw"
    wavs = sorted(raw.glob("fart_*.wav"))
    audio_evidence: list[str] = []
    audio_hashes: list[str] = []
    audio_ok = len(wavs) == 12
    for path in wavs:
        try:
            with wave.open(str(path), "rb") as wav:
                channels = wav.getnchannels(); width = wav.getsampwidth(); rate = wav.getframerate(); frames = wav.getnframes()
                duration = frames / rate
                samples = wav.readframes(frames)
            peak = max(abs(int.from_bytes(samples[i:i+2], "little", signed=True)) for i in range(0, len(samples), 2))
            digest = sha256(path)
            audio_hashes.append(digest)
            valid = channels == 1 and width == 2 and rate == 44_100 and 0.35 <= duration <= 2.10 and peak > 1000
            audio_ok = audio_ok and valid
            audio_evidence.append(f"{path.name}:{duration:.2f}s:{rate}Hz:peak={peak}:{digest[:12]}")
        except Exception as error:
            audio_ok = False
            audio_evidence.append(f"{path.name}:{type(error).__name__}")
    audio_ok = audio_ok and len(set(audio_hashes)) == 12
    add("AUD-001", "audio", audio_ok, "; ".join(audio_evidence) + f"; unique={len(set(audio_hashes)) == 12}")

    manifest = read("app/src/main/AndroidManifest.xml")
    gradle = read("app/build.gradle")
    service = read("app/src/main/java/com/ghostatlas/asslight/ChaosService.java")
    profiles = read("app/src/main/java/com/ghostatlas/asslight/ChaosProfiles.java")
    activity = read("app/src/main/java/com/ghostatlas/asslight/MainActivity.java")
    torch = read("app/src/main/java/com/ghostatlas/asslight/TorchController.java")
    test = read("app/src/androidTest/java/com/ghostatlas/asslight/RuntimeSmokeTest.java")

    allowed_permissions = {
        "android.permission.FOREGROUND_SERVICE", "android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK",
        "android.permission.POST_NOTIFICATIONS", "android.permission.WAKE_LOCK", "android.permission.CAMERA",
    }
    declared = set(re.findall(r'<uses-permission android:name="([^"]+)"', manifest))
    permission_ok = declared == allowed_permissions and "android.permission.INTERNET" not in manifest
    add("SAFE-001", "safety", permission_ok, f"declared={sorted(declared)}; INTERNET absent={'android.permission.INTERNET' not in manifest}")

    background_ok = all(token in manifest for token in [
        'android:foregroundServiceType="mediaPlayback"', 'android:exported="false"', 'android:stopWithTask="false"'
    ]) and "ACTION_STOP" in service and "startForeground(" in service and "START_NOT_STICKY" in service
    add("BG-001", "background", background_ok, "private mediaPlayback service + visible foreground notification + stop action")

    rng_ok = all(token in service + profiles for token in [
        "SecureRandom", "MIN_SECONDS", "MAX_SECONDS", "MIN_VOLUME", "MAX_VOLUME", "random.nextInt", "random.nextFloat"
    ])
    add("RNG-001", "randomization", rng_ok, "SecureRandom drives bounded sound, interval, volume, and oracle selection")

    prepared = service.find("mediaPlayer.start()")
    receipt = service.find('"PLAYBACK_STARTED"')
    add("AUD-002-SRC", "audio", prepared >= 0 and receipt > prepared, "PLAYBACK_STARTED receipt follows MediaPlayer.start()")

    cleanup_tokens = [
        "removeCallbacksAndMessages", "releasePlayer", "torch.setEnabled(false)", "wakeLock.release",
        'putBoolean("running", false)', "stopForeground", "stopSelf",
    ]
    add("CTL-001", "control", all(token in service for token in cleanup_tokens), f"cleanup={cleanup_tokens}")

    sdk_ok = all(token in gradle for token in [
        "compileSdk 35", "targetSdk 35", "minSdk 23", "JavaVersion.VERSION_17",
        "applicationId 'com.ghostatlas.asslight.rc'", "signingConfig signingConfigs.debug",
    ])
    add("SDK-001", "platform", sdk_ok, "compileSdk=35 targetSdk=35 minSdk=23 Java=17 RC identity isolated")

    ui_ok = all(token in activity for token in [
        "OPEN THE BROWN PORTAL", "SEAL THE PORTAL", "RUN SECA PREFLIGHT", "TEST GUST",
        "Flash mode is off by default", "No covert startup", "consensual tension-breaking",
    ]) and "screenOrientation" not in manifest
    add("UX-001", "user control", ui_ok, "explicit start/stop/test/audit/safety controls; adaptive orientation")

    torch_ok = all(token in torch + manifest + activity + service for token in [
        "CAMERA", "FLASH_INFO_AVAILABLE", "setTorchMode", "pulse(180L)", "android:required=\"false\"", "setChecked(false)",
    ])
    add("TOR-001", "flashlight", torch_ok, "source verified; physical rear-torch output remains HITL", hard=False, partial=torch_ok)

    tests_ok = all(token in test for token in [
        "embeddedSoundPreparesAndStartsWithBoundedReceipt", "foregroundSessionStartsAndPortalSealClearsRuntimeState",
        "PLAYBACK_STARTED", "assertFalse",
    ])
    add("TST-001", "tests", tests_ok, "instrumentation covers actual playback receipt and Portal Seal state cleanup")

    network_deny = not any(token in "\n".join([manifest, gradle, service, activity]) for token in [
        "INTERNET", "HttpURLConnection", "OkHttp", "WebView", "Firebase", "AdvertisingId",
    ])
    add("NET-001", "privacy", network_deny, "no network permission, client, analytics, advertising, or web surface")

    add("PLAY-001", "Google Play", True, "AAB is technical candidate only; private Play upload key and console review remain blocked", hard=False, partial=True)


def audit_artifact(args: argparse.Namespace) -> None:
    apk = Path(args.apk); aab = Path(args.aab)
    artifact_ok = apk.is_file() and apk.stat().st_size > 10_000 and aab.is_file() and aab.stat().st_size > 10_000
    add("ART-001", "artifact", artifact_ok, f"apk={apk.exists()}:{apk.stat().st_size if apk.exists() else 0}; aab={aab.exists()}:{aab.stat().st_size if aab.exists() else 0}")

    signer = Path(args.apksigner_receipt).read_text(errors="replace") if args.apksigner_receipt else ""
    signer_ok = "Verifies" in signer and ("Verified using v2 scheme" in signer or "Verified using v3 scheme" in signer)
    add("SIG-001", "signing", signer_ok, signer[:800].replace("\n", " "))

    badging = Path(args.badging_receipt).read_text(errors="replace") if args.badging_receipt else ""
    package_ok = "package: name='com.ghostatlas.asslight.rc'" in badging and "targetSdkVersion:'35'" in badging
    add("PKG-001", "package", package_ok, "RC package and target SDK 35 found in aapt receipt")
    add("SAFE-002", "safety", "android.permission.INTERNET" not in badging, "APK badging contains no INTERNET permission")


def audit_runtime(args: argparse.Namespace) -> None:
    install = Path(args.install_receipt).read_text(errors="replace") if args.install_receipt else ""
    launch = Path(args.launch_receipt).read_text(errors="replace") if args.launch_receipt else ""
    add("INS-001", "install", "Success" in install, install.strip()[:500])
    add("LCH-001", "launch", "Events injected: 1" in launch or "Monkey finished" in launch, launch.strip()[-700:])

    result_root = Path(args.test_results)
    xml_files = sorted(result_root.rglob("*.xml")) if result_root.exists() else []
    merged = "\n".join(path.read_text(errors="replace") for path in xml_files)
    no_failures = bool(xml_files) and not re.search(r'failures="[1-9]', merged) and not re.search(r'errors="[1-9]', merged)
    playback = "embeddedSoundPreparesAndStartsWithBoundedReceipt" in merged
    portal = "foregroundSessionStartsAndPortalSealClearsRuntimeState" in merged
    add("AUD-002", "audio runtime", no_failures and playback, f"xml={len(xml_files)}; playback_test={playback}; no_failures={no_failures}")
    add("BG-002", "background runtime", no_failures and portal, f"portal_test={portal}; no_failures={no_failures}")

    add("PHY-001", "physical device", True, "speaker quality, rear torch, screen lock, and OEM battery behavior remain manual phone gates", hard=False, partial=True)


def audit_final() -> None:
    inputs = []
    for name in ["SECA_SOURCE_AUDIT.json", "SECA_ARTIFACT_AUDIT.json", "SECA_RUNTIME_AUDIT.json"]:
        path = RECEIPTS / name
        if not path.exists():
            add(f"FINAL-{name}", "receipt", False, f"missing {name}")
            continue
        data = json.loads(path.read_text())
        inputs.append(data)
        add(f"FINAL-{data['stage'].upper()}", "receipt", data["overall"] != "BLOCKED", f"{name} overall={data['overall']}")
    hard_failures = [g for data in inputs for g in data.get("gates", []) if g.get("hard") and g.get("state") != "VERIFIED"]
    add("PROMOTE-001", "promotion", not hard_failures, f"hard_failures={len(hard_failures)}; RC downloadable only; Play and physical gates remain partial")
    add("PROMOTE-PLAY", "Google Play", True, "production signing, content listing, Data Safety submission, and Play review remain explicitly unverified", hard=False, partial=True)
    add("PROMOTE-PHONE", "physical phone", True, "manual phone receipt remains required before claiming flashlight/lock-screen/OEM completion", hard=False, partial=True)


def parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser()
    p.add_argument("--stage", required=True, choices=["source", "artifact", "runtime", "final"])
    p.add_argument("--apk")
    p.add_argument("--aab")
    p.add_argument("--apksigner-receipt")
    p.add_argument("--badging-receipt")
    p.add_argument("--test-results")
    p.add_argument("--install-receipt")
    p.add_argument("--launch-receipt")
    return p


def main() -> int:
    args = parser().parse_args()
    if args.stage == "source": audit_source()
    elif args.stage == "artifact": audit_artifact(args)
    elif args.stage == "runtime": audit_runtime(args)
    else: audit_final()
    state = write_receipt(args.stage)
    return 1 if state == "BLOCKED" else 0


if __name__ == "__main__":
    sys.exit(main())
