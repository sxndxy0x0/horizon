#!/usr/bin/env python3
"""Horizon's agent for the real Minecraft: Java Edition (docs/design/java-mods-everything.md, "into the real mod").

    python tools/java_accel.py build                     # .local/java-accel/: the agent jar, ASM, the native libraries
    python tools/java_accel.py run --profile "Fabric 26.3" --world SAVE [--accel light,wgpu]

build compiles mods/horizon/src/accel/ against the player's own 26.3 jar: boot/ as the agent's classes, runtime/
under runtime/ in the jar (the agent defines those into the loader that defines the game), and builds the native
libraries (horizon-shm-probe: memory and light; horizon-wgpu-backend: drawing).

run starts a launcher profile (vanilla 26.3 or a loader's, such as Fabric 26.3) with the agent in a scratch game
folder (the player's saves, options and configs are never read or written), plays a save, and reads the log until
the accelerators report in: that the runtime went into the game's loader, that Rust propagated light (server and
client), and which graphics backend draws. Then it ends the game.
"""

from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import sys
import threading
import time
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "mods" / "horizon" / "src" / "accel"
OUT = ROOT / ".local" / "java-accel"
sys.path.insert(0, str(ROOT / "tools"))
import loader_probe  # noqa: E402
import shared_memory_probe  # noqa: E402


def cargo_target() -> Path:
    meta = json.loads(subprocess.run(["cargo", "metadata", "--format-version", "1", "--no-deps"], cwd=ROOT, check=True,
                                     capture_output=True, text=True).stdout)
    return Path(meta["target_directory"]) / "release"


def library_name(crate: str) -> str:
    if sys.platform == "win32":
        return f"{crate}.dll"
    return f"lib{crate}.dylib" if sys.platform == "darwin" else f"lib{crate}.so"


def build(minecraft: Path, version: str, java_home: Path | None, out: Path) -> Path:
    versions, libraries = minecraft / "versions", minecraft / "libraries"
    libs, _, _ = loader_probe.classpath(versions / version / f"{version}.json", versions, libraries)
    game = shared_memory_probe.client_jar(versions, version)
    asm = []
    for artifact in ("asm", "asm-tree"):
        found = sorted((libraries / "org" / "ow2" / "asm" / artifact).glob(f"*/{artifact}-*.jar"),
                       key=lambda p: [int(x) if x.isdigit() else 0 for x in p.parent.name.split(".")])
        if not found:
            raise SystemExit(f"no {artifact} jar in {libraries}: start a Fabric or NeoForge profile once")
        asm.append(found[-1])
    javac = str(java_home / "bin" / "javac") if java_home else "javac"
    classes = out / "classes"
    shutil.rmtree(classes, ignore_errors=True)
    boot, runtime = classes / "boot", classes / "runtime"
    boot.mkdir(parents=True)
    runtime.mkdir(parents=True)
    sep = os.pathsep
    subprocess.run([javac, "--release", "25", "-nowarn", "-cp", sep.join(map(str, asm)), "-d", str(boot),
                    *map(str, (SRC / "boot").rglob("*.java"))], check=True)
    subprocess.run([javac, "--release", "25", "-nowarn", "-cp", sep.join(map(str, [boot, *asm, *libs, game])),
                    "-d", str(runtime), *map(str, (SRC / "runtime").rglob("*.java"))], check=True)
    jar = out / "horizon-accel.jar"
    # No Class-Path: the agent adds the ASM beside it only when the class path has none (Agent.asm).
    manifest = "Manifest-Version: 1.0\nPremain-Class: dev.horizonlab.accel.boot.Agent\n"
    with zipfile.ZipFile(jar, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("META-INF/MANIFEST.MF", manifest)
        for f in sorted(boot.rglob("*.class")):
            z.write(f, f.relative_to(boot).as_posix())
        for f in sorted(runtime.rglob("*.class")):
            z.write(f, "runtime/" + f.relative_to(runtime).as_posix())
    for a in asm:
        shutil.copyfile(a, out / a.name)
    subprocess.run(["cargo", "build", "--release", "-p", "horizon-shm-probe", "-p", "horizon-wgpu-backend"], cwd=ROOT,
                   check=True)
    target = cargo_target()
    for crate in ("horizon_shm_probe", "horizon_wgpu_backend"):
        shutil.copyfile(target / library_name(crate), out / library_name(crate))
    print(f"built {jar} ({sum(1 for _ in runtime.rglob('*.class'))} runtime classes)")
    return jar


OPTIONS = "onboardAccessibility:false\nskipMultiplayerWarning:true\njoinedFirstServer:true\ntutorialStep:none\n"

# What a run waits for in the log, by name.
MARKERS = {
    "agent": "[horizon-accel] on:",
    "runtime injected": "[horizon-accel] runtime in",
    "light on the server": "[shm] light: Rust propagates on Worker",
    "light on the client": "[shm] light: Rust propagates on Render thread",
    "world joined": "joined the game",
}
# What a run may find instead of Rust's light: the game's own, because a loader or mod changed a class it guards.
FALLBACK = "[shm] light stays Java"



def run(args) -> int:
    mc = args.minecraft.resolve()
    versions, libraries = mc / "versions", mc / "libraries"
    profile_json = versions / args.profile / f"{args.profile}.json"
    libs, main_class, game_jar = loader_probe.classpath(profile_json, versions, libraries)
    if not game_jar.exists():
        # The vanilla version the profile stands on, wherever the launcher keeps its jar (a loader profile's copy).
        game_jar = shared_memory_probe.client_jar(versions, loader_probe.vanilla_jar(profile_json, versions).parent.name)
    # A loader installed into a folder of its own (as the loader probe does) takes what it lacks from the player's
    # launcher folder, read only.
    player = args.player_minecraft.resolve()
    libs = [l if l.exists() else player / "libraries" / l.relative_to(libraries) for l in libs]
    out = (args.out or ROOT / ".local" / "java-accel-run" / args.profile.replace(" ", "-")).resolve()
    game_dir = out / "game"
    # Only what the run makes again: `out` also holds assets/objects, a link to the player's own assets.
    shutil.rmtree(game_dir, ignore_errors=True)
    (game_dir / "saves").mkdir(parents=True)
    (game_dir / "options.txt").write_text(OPTIONS, encoding="utf-8")
    shutil.copytree(args.world, game_dir / "saves" / "accel")
    (game_dir / "mods").mkdir()
    for mod in args.mods:
        # The player's mod jars, copied (read only): the pack this run plays.
        shutil.copyfile(mod, game_dir / "mods" / mod.name)
    (game_dir / "saves" / "accel" / "session.lock").unlink(missing_ok=True)
    natives = out / "natives"
    jars = [j if j.exists() else player / "libraries" / j.relative_to(libraries)
            for j in loader_probe.native_jars(profile_json, versions, libraries)]
    loader_probe.extract_natives([j for j in jars if j.exists()], natives)
    assets = shared_memory_probe_assets(player if not (mc / "assets" / "objects").exists() else mc, profile_json,
                                        versions, out)
    jar = OUT / "horizon-accel.jar"
    if not jar.exists():
        print(f"no {jar}: run `python tools/java_accel.py build` first", file=sys.stderr)
        return 1
    java = str(args.java_home / "bin" / "java") if args.java_home else "java"
    index = asset_index(profile_json, versions)
    # As the version file asks (its jvm arguments, which a launcher passes): a stack shadow zone native code fits.
    cmd = [java, "-Xmx3g", "-Xss1M", "-XX:StackShadowPages=32", "--enable-native-access=ALL-UNNAMED",
           f"-Djava.library.path={natives}",
           f"-javaagent:{jar}", f"-Dhorizon.accel={args.accel}",
           f"-Dfabric.gameJarPath={game_jar}", *loader_probe.profile_args(profile_json, versions, libraries, "jvm"),
           "-cp", os.pathsep.join(map(str, [*libs, game_jar])), main_class,
           *loader_probe.profile_args(profile_json, versions, libraries, "game"),
           "--gameDir", str(game_dir), "--assetsDir", str(assets), "--assetIndex", index, "--version", args.profile,
           "--accessToken", "0", "--username", "Horizon", "--width", "854", "--height", "480",
           "--quickPlaySingleplayer", "accel"]
    log_path = out / "run.log"
    seen: dict[str, str] = {}
    want = [m for m in MARKERS if m != "light on the client" or "light" in args.accel]
    if "light" not in args.accel:
        want = [m for m in want if not m.startswith("light")]
    print(f"[accel] {args.profile}: {len(libs)} libraries, main {main_class}, log {log_path}")
    with open(log_path, "w", encoding="utf-8") as log:
        proc = subprocess.Popen(cmd, cwd=game_dir, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
                                encoding="utf-8", errors="replace")
        backend = []

        def read() -> None:
            assert proc.stdout
            for line in proc.stdout:
                log.write(line)
                for name, marker in MARKERS.items():
                    if marker in line and name not in seen:
                        seen[name] = line.strip()
                if "Using graphics backend" in line and not backend:
                    backend.append(line.strip())
                if FALLBACK in line and "fallback" not in seen:
                    seen["fallback"] = line.strip()
        reader = threading.Thread(target=read, daemon=True)
        reader.start()
        start = time.time()
        def done() -> bool:
            # With light the game kept (a mod changed a class it guards), Rust's light markers never come.
            need = [m for m in want if not ("fallback" in seen and m.startswith("light"))]
            return all(m in seen for m in need)
        while proc.poll() is None and time.time() - start < args.timeout:
            if done() and time.time() - start > args.settle:
                break
            time.sleep(1)
        exited = proc.poll()
        if exited is None:
            proc.kill()
        proc.wait()
        reader.join(timeout=10)
    print(f"- graphics: {backend[0] if backend else 'not seen'}")
    if "fallback" in seen:
        print(f"- {seen['fallback'][-300:]}")
    for name in want:
        print(f"- {name}: {'yes' if name in seen else 'NO'}" + (f"  ({seen[name][-120:]})" if name in seen else ""))
    if exited is not None:
        print(f"- the game exited by itself (code {exited}); see {log_path}")
    ok = exited is None and done()
    print("ok" if ok else "FAILED")
    return 0 if ok else 1


def asset_index(profile_json: Path, versions: Path) -> str:
    data = json.loads(profile_json.read_text(encoding="utf-8"))
    while "assetIndex" not in data and data.get("inheritsFrom"):
        parent = data["inheritsFrom"]
        data = json.loads((versions / parent / f"{parent}.json").read_text(encoding="utf-8"))
    return data["assetIndex"]["id"]


def shared_memory_probe_assets(mc: Path, profile_json: Path, versions: Path, out: Path) -> Path:
    """A scratch assets folder: the index copied, the objects linked read only (as the P3 probe makes it)."""
    import wgpu_backend_probe
    return wgpu_backend_probe.assets_dir(mc / "assets", asset_index(profile_json, versions), out)


def main() -> int:
    default_mc = Path(os.environ.get("APPDATA", Path.home())) / ".minecraft"
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="what", required=True)
    b = sub.add_parser("build")
    r = sub.add_parser("run")
    for p in (b, r):
        p.add_argument("--minecraft", type=Path, default=default_mc)
        p.add_argument("--java-home", type=Path)
    b.add_argument("--version", default="26.3")
    r.add_argument("--profile", default="26.3", help="a launcher profile under versions/ (26.3, Fabric 26.3, ...)")
    r.add_argument("--world", type=Path, required=True, help="a save to play (copied)")
    r.add_argument("--accel", default="light,wgpu", help="what the agent accelerates: light, wgpu (comma-separated)")
    r.add_argument("--out", type=Path)
    r.add_argument("--player-minecraft", type=Path, default=default_mc,
                   help="the player's launcher folder, for libraries and assets a --minecraft folder lacks (read only)")
    r.add_argument("--mods", type=Path, nargs="*", default=[], help="mod jars to play with (copied)")
    r.add_argument("--timeout", type=int, default=600, help="seconds to wait for the markers")
    r.add_argument("--settle", type=int, default=20, help="seconds to keep playing at least")
    args = ap.parse_args()
    if args.what == "build":
        build(args.minecraft.resolve(), args.version, args.java_home, OUT)
        return 0
    return run(args)


if __name__ == "__main__":
    sys.exit(main())
