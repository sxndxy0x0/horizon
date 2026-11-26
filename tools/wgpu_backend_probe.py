#!/usr/bin/env python3
"""P3 of docs/design/java-mods-everything.md: the real Java client draws through wgpu.

Starts the 26.3 client (in a scratch game folder: no saves or options of the player's are read or written) once on
the game's own OpenGL backend and once on Horizon's wgpu backend (mods/horizon/src/wgpu-backend/ over
crates/horizon-wgpu-backend), each until the title screen has drawn for a while; the game's own screenshot code
then reads the frame back and the run exits. The two frames are compared pixel by pixel.

The title screen is held still for the comparison: the panorama does not turn (the `panoramaScrollSpeed` option)
and the random splash text is taken off.

    python tools/wgpu_backend_probe.py                      # opengl and wgpu
    python tools/wgpu_backend_probe.py --backends opengl,vulkan,wgpu
"""

from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "tools"))
import loader_probe  # noqa: E402
import shared_memory_probe  # noqa: E402

SRC = ROOT / "mods" / "horizon" / "src" / "wgpu-backend"
# The product code the probe runs (the Horizon agent's boot and runtime classes): see tools/java_accel.py.
ACCEL = ROOT / "mods" / "horizon" / "src" / "accel"

OPTIONS = """onboardAccessibility:false
panoramaScrollSpeed:0.0
narrator:0
tutorialStep:none
skipMultiplayerWarning:true
joinedFirstServer:true
soundCategory_master:0.0
realmsNotifications:false
"""

# A world held still for the comparison: a near render distance (the chunks the world was generated with, so no
# run generates new ones differently) and no pause when the window loses focus.
WORLD_OPTIONS = """renderDistance:4
simulationDistance:5
pauseOnLostFocus:false
"""

# Frames as fast as the backend draws them (260 is the game's "unlimited").
UNCAPPED_OPTIONS = """enableVsync:false
maxFps:260
"""


def rust_library() -> Path:
    subprocess.run(["cargo", "build", "-p", "horizon-wgpu-backend", "--release"], cwd=ROOT, check=True)
    meta = json.loads(subprocess.run(["cargo", "metadata", "--format-version", "1", "--no-deps"], cwd=ROOT,
                                     check=True, capture_output=True, text=True).stdout)
    release = Path(meta["target_directory"]) / "release"
    for name in ("horizon_wgpu_backend.dll", "libhorizon_wgpu_backend.so", "libhorizon_wgpu_backend.dylib"):
        if (release / name).exists():
            return release / name
    raise SystemExit(f"no horizon-wgpu-backend library in {release}")


def build_jar(java_home: Path | None, cp: list[Path], out: Path) -> Path:
    javac = str(java_home / "bin" / "javac") if java_home else "javac"
    jar_tool = str(java_home / "bin" / "jar") if java_home else "jar"
    classes = out / "build" / "classes"
    shutil.rmtree(classes, ignore_errors=True)
    classes.mkdir(parents=True)
    subprocess.run([javac, "--release", "25", "-nowarn", "-cp", os.pathsep.join(map(str, cp)), "-d", str(classes),
                    *map(str, SRC.rglob("*.java")), *map(str, ACCEL.rglob("*.java"))], check=True)
    manifest = out / "build" / "MANIFEST.MF"
    manifest.write_text("Premain-Class: dev.horizonlab.wgpu.Agent\n", encoding="utf-8")
    jar = out / "build" / "wgpu-backend.jar"
    jar.unlink(missing_ok=True)
    subprocess.run([jar_tool, "--create", "--file", str(jar), "--manifest", str(manifest), "-C", str(classes), "."],
                   check=True)
    return jar


def assets_dir(real: Path, index: str, out: Path) -> Path:
    """A scratch assets folder: the index copied, the objects linked (read only), anything the game writes here."""
    assets = out / "assets"
    (assets / "indexes").mkdir(parents=True, exist_ok=True)
    shutil.copyfile(real / "indexes" / f"{index}.json", assets / "indexes" / f"{index}.json")
    objects = assets / "objects"
    if not objects.exists():
        if os.name == "nt":
            subprocess.run(["cmd", "/c", "mklink", "/J", str(objects), str(real / "objects")], check=True,
                           capture_output=True)
        else:
            objects.symlink_to(real / "objects")
    return assets


def compare(a: Path, b: Path, diff_out: Path) -> dict[str, float]:
    from PIL import Image, ImageChops

    x = Image.open(a).convert("RGB")
    y = Image.open(b).convert("RGB")
    if x.size != y.size:
        return {"size_mismatch": 1.0}
    d = ImageChops.difference(x, y)
    hist = d.convert("L").histogram()
    total = x.size[0] * x.size[1]
    over = lambda t: sum(hist[t + 1:]) / total  # noqa: E731
    channel_sum = sum(sum(v * c for v, c in enumerate(d.getchannel(i).histogram())) for i in range(3))
    d.point(lambda v: min(255, v * 8)).save(diff_out)
    return {
        "pixels": float(total),
        "mean_abs_diff": channel_sum / (3 * total),
        "share_over_2": over(2),
        "share_over_16": over(16),
        "share_over_64": over(64),
    }


def main() -> int:
    default_mc = Path(os.environ.get("APPDATA", Path.home())) / ".minecraft"
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--minecraft", type=Path, default=default_mc)
    ap.add_argument("--version", default="26.3")
    ap.add_argument("--out", type=Path, default=ROOT / ".local" / "wgpu-backend-probe")
    ap.add_argument("--java-home", type=Path)
    ap.add_argument("--backends", default="opengl,wgpu")
    ap.add_argument("--frames", type=int, default=None, help="frames before the capture (120 title, 600 world)")
    ap.add_argument("--world", type=Path, help="a saved world to open (quick play) and capture instead of the title "
                    "screen: one shared_memory_probe.py generated, .local/shared-memory-probe/world-generated/"
                    "universe/gametestworld")
    ap.add_argument("--view", default="8.5,100,8.5,135,30", help="the camera in the world: x,y,z,yaw,pitch")
    ap.add_argument("--hold", type=float, default=None,
                    help="seconds at least between the first frame and the capture (30 in a world, where the sky's "
                         "colors blend over time; 0 on the title screen)")
    ap.add_argument("--uncapped", action="store_true",
                    help="no vsync and no frame cap, so the frame times measure the backend (the frames still compare)")
    args = ap.parse_args()

    mc = args.minecraft.resolve()
    versions, libraries = mc / "versions", mc / "libraries"
    profile = versions / args.version / f"{args.version}.json"
    libs, main_class, _ = loader_probe.classpath(profile, versions, libraries)
    game = shared_memory_probe.client_jar(versions, args.version)
    out = args.out.resolve()
    out.mkdir(parents=True, exist_ok=True)
    natives = out / "natives"
    loader_probe.extract_natives([p for p in loader_probe.native_jars(profile, versions, libraries) if p.exists()],
                                 natives)
    lib = rust_library()
    asm = []
    for artifact in ("asm", "asm-tree"):
        found = sorted((libraries / "org" / "ow2" / "asm" / artifact).glob(f"*/{artifact}-*.jar"),
                       key=lambda p: [int(x) if x.isdigit() else 0 for x in p.parent.name.split(".")])
        if not found:
            print(f"no {artifact} jar in {libraries}: start a Fabric or NeoForge profile once", file=sys.stderr)
            return 1
        asm.append(found[-1])
    cp = [*asm, *libs, game]
    jar = build_jar(args.java_home, cp, out)
    java = str(args.java_home / "bin" / "java") if args.java_home else "java"
    index = json.loads(profile.read_text(encoding="utf-8"))["assetIndex"]["id"]
    assets = assets_dir(mc / "assets", index, out)

    frames = args.frames or (600 if args.world else 120)
    results = {}
    for backend in args.backends.split(","):
        game_dir = out / f"game-{backend}"
        shutil.rmtree(game_dir, ignore_errors=True)
        game_dir.mkdir(parents=True)
        (game_dir / "options.txt").write_text(OPTIONS + (WORLD_OPTIONS if args.world else "")
                                              + (UNCAPPED_OPTIONS if args.uncapped else ""), encoding="utf-8")
        world_args = []
        if args.world:
            shutil.copytree(args.world, game_dir / "saves" / "probe")
            (game_dir / "saves" / "probe" / "session.lock").unlink(missing_ok=True)
            world_args = ["--quickPlaySingleplayer", "probe"]
        shot = out / f"{backend}.png"
        shot.unlink(missing_ok=True)
        # The launcher's JVM arguments from the version file that change how native code runs: a larger stack
        # shadow zone (native code past it kills the process without a JVM crash report) and the stack size.
        # hz.render.only: the one backend alone, never quietly the game's own (HZ_JVM_EXTRA, after it, may differ).
        cmd = [java, "-Xmx2g", "-Xss1M", "-XX:StackShadowPages=32", "--enable-native-access=ALL-UNNAMED", f"-XX:ErrorFile={out / (backend + '-hs_err.log')}", "-Dhz.render.only=true", *os.environ.get("HZ_JVM_EXTRA", "").split(), f"-Djava.library.path={natives}",
               f"-javaagent:{jar}", f"-Dhz.render={backend}", f"-Dhz.capture={shot}",
               f"-Dhz.capture.frames={frames}", f"-Dhz.wgpu.lib={lib}", "-Dhz.capture.world=" + str(bool(args.world)).lower(),
               f"-Dhz.capture.view={args.view}", "-Dhz.capture.hold=" + str(args.hold if args.hold is not None else (30 if args.world else 0)), "-Dhz.trace=" + str(bool(os.environ.get('HZ_WGPU_TRACE'))).lower(),
               "-cp", os.pathsep.join(map(str, [jar, *cp])), main_class,
               "--gameDir", str(game_dir), "--assetsDir", str(assets), "--assetIndex", index,
               "--version", args.version, "--accessToken", "0", "--username", "Horizon",
               "--width", "854", "--height", "480", *world_args]
        log_path = out / f"{backend}.log"
        with open(log_path, "w", encoding="utf-8") as log:
            try:
                code = subprocess.run(cmd, cwd=game_dir, stdout=log, stderr=subprocess.STDOUT,
                                      timeout=600).returncode
            except subprocess.TimeoutExpired:
                code = -1
        ok = code == 0 and shot.exists()
        info = {}
        if Path(f"{shot}.txt").exists():
            for line in Path(f"{shot}.txt").read_text(encoding="utf-8").splitlines():
                k, _, v = line.partition("\t")
                info[k] = v
        results[backend] = {"ok": ok, "exit": code, "log": str(log_path), **info}
        print(f"{backend}: {'frame saved' if ok else f'failed (exit {code}); see {log_path}'}")

    report = ["# P3 report", ""]
    for backend, r in results.items():
        report.append(f"- {backend}: " + (f"frame after {r.get('frames')} frames, {r.get('ms per frame')} ms "
                                          f"a frame, longest stall {r.get('longest tick gap ms')} ms, {r.get('size')}, drawn by {r.get('backend')}" if r["ok"] else f"failed, see {r['log']}"))
    ok = all(r["ok"] for r in results.values())
    base = "opengl" if "opengl" in results else None
    for backend in results:
        if base and backend != base and results[base]["ok"] and results[backend]["ok"]:
            c = compare(out / f"{base}.png", out / f"{backend}.png", out / f"diff-{base}-{backend}.png")
            report.append(f"- {backend} against {base}: " + ", ".join(f"{k} {v:.4g}" for k, v in c.items()))
    text = "\n".join(report) + "\n"
    (out / "report.md").write_text(text, encoding="utf-8")
    print(text)
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
