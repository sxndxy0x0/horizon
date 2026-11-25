#!/usr/bin/env python3
"""P2 of docs/design/java-mods-everything.md: vanilla's block storage on memory Rust owns.

Runs the same vanilla block-storage work twice in one JVM setup: on vanilla's own SimpleBitStorage, and with
the agent in mods/horizon/src/shared-memory-probe/ putting every SimpleBitStorage the game creates in memory
that crates/horizon-shm-probe owns (Java reads and writes it in place through FFM). Then compares the two runs
(every section's cells, network bytes, saved form, copies and read-backs), reports the checks Rust made on the
same memory with horizon-core's own code, and the speed of both.

    python tools/shared_memory_probe.py                  # the launcher's 26.3 install
    python tools/shared_memory_probe.py --minecraft DIR --version 26.3
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "tools"))
import loader_probe  # noqa: E402

SRC = ROOT / "mods" / "horizon" / "src" / "shared-memory-probe"


def client_jar(versions: Path, version: str) -> Path:
    """The version's client jar: its own, or a launcher profile's copy with the same SHA-1 (Fabric's)."""
    data = json.loads((versions / version / f"{version}.json").read_text(encoding="utf-8"))
    own = versions / version / f"{version}.jar"
    if own.exists():
        return own
    want = data["downloads"]["client"]
    for jar in versions.glob("*/*.jar"):
        if jar.stat().st_size == want["size"] and hashlib.sha1(jar.read_bytes()).hexdigest() == want["sha1"]:
            return jar
    raise SystemExit(f"no client jar for {version}: start it once in the launcher")


def rust_library() -> Path:
    subprocess.run(["cargo", "build", "-p", "horizon-shm-probe", "--release"], cwd=ROOT, check=True)
    meta = json.loads(subprocess.run(["cargo", "metadata", "--format-version", "1", "--no-deps"], cwd=ROOT,
                                     check=True, capture_output=True, text=True).stdout)
    release = Path(meta["target_directory"]) / "release"
    for name in ("horizon_shm_probe.dll", "libhorizon_shm_probe.so", "libhorizon_shm_probe.dylib"):
        if (release / name).exists():
            return release / name
    raise SystemExit(f"no horizon-shm-probe library in {release}")


def build_jar(java_home: Path | None, cp: list[Path], out: Path) -> Path:
    javac = str(java_home / "bin" / "javac") if java_home else "javac"
    jar_tool = str(java_home / "bin" / "jar") if java_home else "jar"
    classes = out / "build" / "classes"
    shutil.rmtree(classes, ignore_errors=True)
    classes.mkdir(parents=True)
    subprocess.run([javac, "--release", "25", "-nowarn", "-cp", os.pathsep.join(map(str, cp)), "-d", str(classes),
                    *map(str, SRC.rglob("*.java"))], check=True)
    manifest = out / "build" / "MANIFEST.MF"
    manifest.write_text("Premain-Class: dev.horizonlab.shm.Agent\n", encoding="utf-8")
    jar = out / "build" / "shm-probe.jar"
    jar.unlink(missing_ok=True)
    subprocess.run([jar_tool, "--create", "--file", str(jar), "--manifest", str(manifest), "-C", str(classes), "."],
                   check=True)
    return jar


def read(path: Path) -> list[str]:
    return path.read_text(encoding="utf-8").splitlines()


def main() -> int:
    default_mc = Path(os.environ.get("APPDATA", Path.home())) / ".minecraft"
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--minecraft", type=Path, default=default_mc)
    ap.add_argument("--version", default="26.3")
    ap.add_argument("--out", type=Path, default=ROOT / ".local" / "shared-memory-probe")
    ap.add_argument("--java-home", type=Path)
    args = ap.parse_args()

    mc = args.minecraft.resolve()
    versions, libraries = mc / "versions", mc / "libraries"
    libs, _, _ = loader_probe.classpath(versions / args.version / f"{args.version}.json", versions, libraries)
    missing = [p for p in libs if not p.exists()]
    if missing:
        print("missing libraries (start the version once in the launcher):", *missing, sep="\n  ", file=sys.stderr)
        return 1
    game = client_jar(versions, args.version)
    out = args.out.resolve()
    out.mkdir(parents=True, exist_ok=True)
    lib = rust_library()
    # The agent's ASM: vanilla ships none; loaders bring theirs into the launcher's libraries.
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
    run_dir = out / "run"
    run_dir.mkdir(exist_ok=True)

    results = {}
    for mode in ("vanilla", "rust"):
        agent = [f"-javaagent:{jar}", "-Dhz.mode=rust"] if mode == "rust" else []
        cmd = [java, "-Xmx2g", "--enable-native-access=ALL-UNNAMED", f"-Dhz.lib={lib}", *agent,
               "-cp", os.pathsep.join(map(str, [jar, *cp])), "dev.horizonlab.shm.Probe", str(out / f"{mode}.tsv")]
        with open(out / f"{mode}.log", "w", encoding="utf-8") as log:
            code = subprocess.run(cmd, cwd=run_dir, stdout=log, stderr=subprocess.STDOUT, timeout=1800).returncode
        if code != 0 or not (out / f"{mode}.tsv").exists():
            print(f"the {mode} run failed (exit {code}); see {out / f'{mode}.log'}", file=sys.stderr)
            return 1
        results[mode] = read(out / f"{mode}.tsv")

    def lines(mode: str, prefix: str) -> list[str]:
        return [l for l in results[mode] if l.startswith(prefix)]

    van, rus = lines("vanilla", "section"), lines("rust", "section")
    differ = [(a, b) for a, b in zip(van, rus) if a != b]
    report = ["# P2 report", "",
              f"- sections compared: {len(van)} (vanilla) / {len(rus)} (rust)",
              f"- sections that differ: {len(differ) + abs(len(van) - len(rus))}"]
    report += [f"  - vanilla: {a}\n  - rust:    {b}" for a, b in differ[:5]]
    for prefix in ("rust checks", "storage equivalence", "rewritten sites"):
        for l in lines("rust", prefix):
            report.append(f"- {l.replace(chr(9), ': ', 1).replace(chr(9), ', ')}")
    for mode in ("vanilla", "rust"):
        for l in lines(mode, "bench"):
            report.append(f"- {mode} run, {l.replace(chr(9), ': ')}")
    text = "\n".join(report) + "\n"
    (out / "report.md").write_text(text, encoding="utf-8")
    print(text)
    return 0 if not differ and len(van) == len(rus) else 1


if __name__ == "__main__":
    sys.exit(main())
