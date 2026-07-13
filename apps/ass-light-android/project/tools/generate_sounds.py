#!/usr/bin/env python3
"""Generate twelve original, deterministic, Android-safe PCM WAV comedy sounds.

No downloaded or third-party audio is used. Every file is synthesized locally as
44.1 kHz, mono, signed 16-bit PCM and normalized below clipping.
"""
from __future__ import annotations

import hashlib
import json
import math
import random
import struct
import wave
from dataclasses import asdict, dataclass
from pathlib import Path

RATE = 44_100
OUT = Path(__file__).resolve().parents[1] / "app" / "src" / "main" / "res" / "raw"
OUT.mkdir(parents=True, exist_ok=True)


@dataclass(frozen=True)
class Recipe:
    slug: str
    duration: float
    base_hz: float
    wobble_hz: float
    wobble_rate: float
    rasp_rate: float
    noise: float
    wetness: float
    bursts: int
    decay: float
    pitch_drop: float


RECIPES = [
    Recipe("tiny_squeaker", 0.42, 93, 18, 7.0, 28, 0.08, 0.04, 2, 2.4, 18),
    Recipe("dry_pocket_pop", 0.54, 67, 13, 5.7, 21, 0.13, 0.03, 3, 2.0, 13),
    Recipe("double_cheek_knock", 0.72, 58, 11, 4.8, 18, 0.14, 0.08, 4, 1.8, 16),
    Recipe("mud_wizard", 0.88, 49, 9, 3.9, 15, 0.22, 0.22, 5, 1.6, 12),
    Recipe("camp_chair_zip", 0.64, 76, 20, 8.6, 34, 0.11, 0.05, 4, 2.1, 25),
    Recipe("forbidden_didgeridoo", 1.38, 43, 7, 2.8, 12, 0.10, 0.10, 4, 1.25, 9),
    Recipe("portal_misfire", 1.06, 54, 17, 6.4, 24, 0.18, 0.16, 7, 1.4, 22),
    Recipe("ancient_one_turns", 1.72, 36, 6, 2.1, 10, 0.12, 0.12, 6, 1.05, 7),
    Recipe("tent_flap", 0.47, 84, 22, 9.2, 38, 0.09, 0.02, 3, 2.8, 29),
    Recipe("thunder_wook", 1.24, 39, 8, 3.1, 13, 0.17, 0.14, 7, 1.18, 10),
    Recipe("suspicious_frog", 0.81, 62, 15, 5.4, 26, 0.16, 0.18, 6, 1.72, 20),
    Recipe("brown_ragnarok", 1.91, 34, 10, 2.5, 11, 0.24, 0.25, 10, 0.98, 8),
]


def smooth_noise(rng: random.Random, count: int, cutoff: float) -> list[float]:
    out: list[float] = []
    state = 0.0
    alpha = max(0.001, min(0.95, cutoff))
    for _ in range(count):
        state += alpha * ((rng.random() * 2.0 - 1.0) - state)
        out.append(state)
    return out


def synth(index: int, recipe: Recipe) -> list[int]:
    seed = 913_000 + index * 7_919
    rng = random.Random(seed)
    count = int(RATE * recipe.duration)
    breath = smooth_noise(rng, count, 0.025 + recipe.wetness * 0.08)
    grit = smooth_noise(rng, count, 0.22)

    burst_rng = random.Random(seed + 44)
    positions = []
    spacing = recipe.duration / (recipe.bursts + 1)
    for k in range(recipe.bursts):
        positions.append(spacing * (k + 1) + burst_rng.uniform(-0.08, 0.08) * spacing)

    phase = 0.0
    raw: list[float] = []
    for n in range(count):
        t = n / RATE
        progress = t / recipe.duration
        attack = min(1.0, t / 0.018)
        release = max(0.0, 1.0 - progress ** recipe.decay)
        macro = attack * release

        flutter = math.sin(2 * math.pi * recipe.wobble_rate * t)
        pitch = recipe.base_hz + recipe.wobble_hz * flutter - recipe.pitch_drop * progress
        pitch = max(23.0, pitch)
        phase += 2 * math.pi * pitch / RATE

        body = (
            math.sin(phase)
            + 0.42 * math.sin(2.01 * phase + 0.3)
            + 0.21 * math.sin(3.07 * phase + 1.1)
            + 0.09 * math.sin(4.9 * phase)
        )

        rasp = 0.5 + 0.5 * max(0.0, math.sin(2 * math.pi * recipe.rasp_rate * t + index * 0.71))
        burst = 0.0
        for k, center in enumerate(positions):
            width = max(0.008, recipe.duration * (0.013 + 0.004 * (k % 3)))
            burst += math.exp(-((t - center) / width) ** 2) * (0.45 + 0.13 * (k % 3))

        noise_component = recipe.noise * grit[n] * (0.35 + 0.65 * rasp)
        breath_component = recipe.wetness * breath[n] * (0.55 + 0.75 * burst)
        pop_component = burst * (0.14 * math.sin(phase * 1.67) + 0.16 * grit[n])
        value = macro * (0.47 * body * (0.66 + 0.34 * rasp) + noise_component + breath_component + pop_component)
        raw.append(math.tanh(value * 1.35))

    peak = max(abs(v) for v in raw) or 1.0
    gain = 0.88 / peak
    return [max(-32767, min(32767, int(v * gain * 32767))) for v in raw]


def write_wave(path: Path, samples: list[int]) -> None:
    with wave.open(str(path), "wb") as wav:
        wav.setnchannels(1)
        wav.setsampwidth(2)
        wav.setframerate(RATE)
        wav.writeframes(b"".join(struct.pack("<h", sample) for sample in samples))


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main() -> None:
    manifest = []
    for index, recipe in enumerate(RECIPES, 1):
        path = OUT / f"fart_{index:02d}.wav"
        samples = synth(index, recipe)
        write_wave(path, samples)
        manifest.append({
            "resource": path.name,
            "recipe": asdict(recipe),
            "sample_rate_hz": RATE,
            "channels": 1,
            "sample_width_bytes": 2,
            "frames": len(samples),
            "duration_seconds": round(len(samples) / RATE, 4),
            "sha256": sha256(path),
        })
        print(f"generated {path.name}: {len(samples) / RATE:.2f}s {manifest[-1]['sha256'][:12]}")

    manifest_path = OUT / "sound_manifest.json"
    manifest_path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print(f"wrote {manifest_path}")


if __name__ == "__main__":
    main()
