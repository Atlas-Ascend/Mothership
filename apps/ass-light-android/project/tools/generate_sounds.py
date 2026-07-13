#!/usr/bin/env python3
"""Generate twelve deterministic, original, Android-safe ASS-LIGHT WAV assets."""
from __future__ import annotations

import hashlib
import json
import math
import random
import struct
import wave
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "app" / "src" / "main" / "res" / "raw"
RATE = 44_100
SPECS = [
    (0.42, 63.0, 1.8, 0.52), (0.54, 49.0, 2.6, 0.44),
    (0.72, 71.0, 3.2, 0.36), (0.88, 41.0, 1.2, 0.58),
    (0.64, 82.0, 4.4, 0.32), (1.38, 37.0, 2.1, 0.48),
    (1.06, 55.0, 5.0, 0.28), (1.72, 33.0, 1.6, 0.54),
    (0.47, 94.0, 6.2, 0.24), (1.24, 44.0, 3.7, 0.40),
    (0.81, 68.0, 2.9, 0.46), (1.91, 29.0, 1.1, 0.62),
]


def envelope(t: float, duration: float, wobble: float) -> float:
    attack = min(1.0, t / max(0.012, duration * 0.08))
    decay = max(0.0, 1.0 - t / duration) ** (1.4 + wobble * 0.08)
    flutter = 0.72 + 0.28 * math.sin(2.0 * math.pi * (7.0 + wobble) * t) ** 2
    return attack * decay * flutter


def synthesize(index: int, duration: float, base: float, wobble: float, grit: float) -> list[int]:
    rng = random.Random(90_990 + index * 7_919)
    count = int(duration * RATE)
    values: list[float] = []
    filtered_noise = 0.0
    for n in range(count):
        t = n / RATE
        progress = t / duration
        frequency = base * (1.0 + 0.17 * math.sin(2 * math.pi * wobble * t))
        frequency *= 1.0 - 0.23 * progress
        phase = 2 * math.pi * frequency * t
        body = 0.58 * math.sin(phase) + 0.23 * math.sin(2.03 * phase + index)
        body += 0.11 * math.sin(0.49 * phase + 0.7)
        raw_noise = rng.uniform(-1.0, 1.0)
        filtered_noise = 0.91 * filtered_noise + 0.09 * raw_noise
        burble = math.sin(2 * math.pi * (13 + index * 0.7) * t + 2.3 * math.sin(phase * 0.07))
        transient = 0.0
        for center in (0.16, 0.39, 0.67):
            width = 0.018 + index * 0.0007
            transient += math.exp(-((progress - center) ** 2) / width) * rng.uniform(-0.35, 0.35)
        sample = body + grit * filtered_noise + 0.12 * burble + transient
        values.append(sample * envelope(t, duration, wobble))

    peak = max(abs(value) for value in values) or 1.0
    scale = 28_834 / peak
    return [max(-32_767, min(32_767, int(value * scale))) for value in values]


def write_wav(path: Path, samples: list[int]) -> None:
    with wave.open(str(path), "wb") as output:
        output.setnchannels(1)
        output.setsampwidth(2)
        output.setframerate(RATE)
        output.writeframes(b"".join(struct.pack("<h", value) for value in samples))


def inspect(path: Path) -> dict[str, object]:
    with wave.open(str(path), "rb") as source:
        frames = source.readframes(source.getnframes())
        sample_count = len(frames) // 2
        samples = struct.unpack(f"<{sample_count}h", frames)
        peak = max(abs(value) for value in samples)
        rms = int(math.sqrt(sum(value * value for value in samples) / sample_count))
        return {
            "file": path.name,
            "channels": source.getnchannels(),
            "sample_width": source.getsampwidth(),
            "sample_rate": source.getframerate(),
            "duration_seconds": round(source.getnframes() / source.getframerate(), 3),
            "peak": peak,
            "rms": rms,
            "bytes": path.stat().st_size,
            "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
        }


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    records = []
    for index, (duration, base, wobble, grit) in enumerate(SPECS, start=1):
        path = OUT / f"fart_{index:02d}.wav"
        write_wav(path, synthesize(index, duration, base, wobble, grit))
        records.append(inspect(path))

    hashes = {record["sha256"] for record in records}
    if len(hashes) != len(records):
        raise SystemExit("Sound forge produced duplicate assets")
    manifest = {
        "schema": "ghost-atlas.ass-light.sound-manifest.v1",
        "generator": "tools/generate_sounds.py",
        "license": "Original deterministic synthesis generated for ASS-LIGHT",
        "count": len(records),
        "unique_sha256_count": len(hashes),
        "sounds": records,
    }
    (OUT / "sound_manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print(f"Generated {len(records)} unique WAV assets in {OUT}")


if __name__ == "__main__":
    main()
