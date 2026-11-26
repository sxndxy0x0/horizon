#!/usr/bin/env python3
"""P2 of docs/design/java-mods-everything.md: vanilla's block storage and light on memory Rust owns.

The agent in mods/horizon/src/shared-memory-probe/ puts every SimpleBitStorage and DataLayer the game creates in
memory that crates/horizon-shm-probe owns (Java reads and writes it in place through FFM). Two probes, each run on
vanilla's own memory and on Rust's:

- the storage probe: the same block-storage and light work, compared section by section (cells, network bytes,
  saved form, copies, read-backs), checks Rust makes on the same memory, eight threads at once, and speed;
- the world probe, on the real game server (vanilla's game-test server) and a normal world:
  1. vanilla generates the world and saves it;
  2. from copies of that save, vanilla (twice: the control) and then Rust's memory load it, the probe edits it,
     the game lights it, keeps its heightmaps and saves it; every chunk must match;
  3. Rust's memory generates a world of its own (worldgen on the game's worker threads), and vanilla loads that
     save back: what vanilla reads must be what the Rust-memory run held.
  On Rust's memory Horizon's own LevelAccess reads the live world in place on several threads and writes into
  it, and Horizon's block registry is checked against the game's.

    python tools/shared_memory_probe.py                  # the launcher's 26.3 install
    python tools/shared_memory_probe.py --minecraft DIR --version 26.3 --horizon-resources DIR
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
    ap.add_argument("--horizon-resources", type=Path, default=ROOT / ".local" / "horizon-client" / "26.3",
                    help="a folder with Horizon's facts.json, for the registry check (read only: it is copied)")
    ap.add_argument("--radius", type=int, default=6, help="chunks each way from 0, 0 in the world probe")
    ap.add_argument("--no-world", action="store_true", help="skip the world probe")
    ap.add_argument("--light", action="store_true",
                    help="P4 instead: the same saved world edited and lit by vanilla's light engine (twice) and by "
                         "Rust's propagation (-Dhz.light=accel), compared chunk by chunk, with the time each spent")
    ap.add_argument("--reuse", action="store_true", help="with --light: keep the generated world of a run before")
    ap.add_argument("--rust-only", action="store_true",
                    help="with --light: run only Rust's propagation, against the vanilla-engine runs of a run before")
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
    if args.light:
        ok, report = light(args, java, lib, jar, cp, out)
        text = "\n".join(["# P4 report", "", *report]) + "\n"
        (out / "light-report.md").write_text(text, encoding="utf-8")
        print(text)
        return 0 if ok else 1

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
    report = ["# P2 report", "", "## Storage probe", "",
              f"- sections compared: {len(van)} (vanilla) / {len(rus)} (rust)",
              f"- sections that differ: {len(differ) + abs(len(van) - len(rus))}"]
    report += [f"  - vanilla: {a}\n  - rust:    {b}" for a, b in differ[:5]]
    for prefix in ("rust checks", "storage equivalence", "light equivalence", "threads", "rewritten sites"):
        for l in lines("rust", prefix):
            report.append(f"- {l.replace(chr(9), ': ', 1).replace(chr(9), ', ')}")
    for mode in ("vanilla", "rust"):
        for l in lines(mode, "bench"):
            report.append(f"- {mode} run, {l.replace(chr(9), ': ')}")
    ok = not differ and len(van) == len(rus)
    ok &= all("wrong" not in l or l.rstrip().endswith(" 0 wrong") for l in lines("rust", "threads"))
    ok &= all(l.split("\t")[1].startswith("identical") for l in lines("rust", "light equivalence"))

    if not args.no_world:
        world_ok, world_report = world(args, java, lib, jar, cp, out)
        ok &= world_ok
        report += ["", *world_report]
    text = "\n".join(report) + "\n"
    (out / "report.md").write_text(text, encoding="utf-8")
    print(text)
    return 0 if ok else 1


FIELDS = ("blocks", "biomes", "block light", "sky light", "heightmaps", "saved")


def chunk_fields(lines: list[str]) -> dict[str, dict[str, str]]:
    """Each chunk's fields: {"0 0": {"blocks": hash, ...}}."""
    chunks = {}
    for line in lines:
        if line.startswith("chunk\t"):
            parts = line.split("\t")
            chunks[parts[1]] = {p.rsplit(" ", 1)[0]: p.rsplit(" ", 1)[1] for p in parts[2:]}
    return chunks


def world(args, java: str, lib: Path, jar: Path, cp: list[Path], out: Path) -> tuple[bool, list[str]]:
    """The world probe (see the module docstring)."""
    facts = None
    if (args.horizon_resources / "facts.json").exists():
        # Loading the registry writes a cache beside facts.json: load a copy.
        facts = out / "registry"
        facts.mkdir(exist_ok=True)
        shutil.copyfile(args.horizon_resources / "facts.json", facts / "facts.json")

    def run(slug: str, mode: str, edits: int, start_from: str | None, extra: tuple = ()) -> list[str] | str:
        run_dir = out / f"world-{slug}"
        shutil.rmtree(run_dir, ignore_errors=True)
        run_dir.mkdir(parents=True)
        if start_from:
            shutil.copytree(out / f"world-{start_from}" / "universe", run_dir / "universe")
        result = out / f"world-{slug}.tsv"
        result.unlink(missing_ok=True)
        props = [f"-Dhz.mode={mode}", "-Dhz.world=normal", f"-Dhz.out={result}", f"-Dhz.radius={args.radius}",
                 f"-Dhz.edits={edits}", *extra]
        if start_from:
            props.append("-Dhz.keep=true")
        if facts and mode == "rust":
            props.append(f"-Dhz.facts={facts}")
        cmd = [java, "-Xmx6g", "--enable-native-access=ALL-UNNAMED", f"-Dhz.lib={lib}", f"-javaagent:{jar}", *props,
               "-Djava.awt.headless=true", "-cp", os.pathsep.join(map(str, [jar, *cp])), "net.minecraft.gametest.Main",
               "--universe", str(run_dir / "universe")]
        with open(out / f"world-{slug}.log", "w", encoding="utf-8") as log:
            code = subprocess.run(cmd, cwd=run_dir, stdout=log, stderr=subprocess.STDOUT, timeout=3600).returncode
        if code != 0 or not result.exists():
            return f"the {slug} run failed (exit {code}); see {out / f'world-{slug}.log'}"
        return read(result)

    if args.light:
        return run
    report = ["## World probe", ""]
    plan = [("generated", "vanilla", 0, None),
            ("vanilla", "vanilla", 40_000, "generated"),
            ("vanilla-again", "vanilla", 40_000, "generated"),
            ("rust", "rust", 40_000, "generated"),
            ("rust-generated", "rust", 0, None),
            ("vanilla-reads-rust", "vanilla", 0, "rust-generated"),
            ("vanilla-reads-vanilla", "vanilla", 0, "generated")]
    runs = {}
    for slug, mode, edits, start_from in plan:
        got = run(slug, mode, edits, start_from)
        if isinstance(got, str):
            return False, report + [f"- {got}"]
        runs[slug] = got

    ok = True

    def inner(key: str, ring: int) -> bool:
        x, z = map(int, key.split())
        return -args.radius + ring <= x < args.radius - ring and -args.radius + ring <= z < args.radius - ring

    def differing(base: str, other: str, field: str, keys) -> int:
        a, b = chunk_fields(runs[base]), chunk_fields(runs[other])
        return sum(1 for k in keys if a[k].get(field) != b.get(k, {}).get(field))

    def compare(title: str, base: str, other: str, control: tuple[str, str], ring: int) -> None:
        """Every field of `base` against `other`, skipping `ring` rings of chunks at the edge; `control` is the
        same comparison with vanilla on both sides."""
        nonlocal ok
        a, b = chunk_fields(runs[base]), chunk_fields(runs[other])
        keys = [k for k in a if inner(k, ring)]
        edge = [k for k in a if not inner(k, ring)]
        report.append(f"### {title}")
        report.append("")
        report.append(f"- chunks: {len(a)} / {len(b)}" + (f"; compared: the inner {len(keys)} (the outer {ring} rings"
                      " are still generated and lit by their neighbours after the save is loaded, in vanilla too)"
                      if ring else ""))
        ok &= len(a) == len(b) > 0 and set(a) == set(b)
        for field in FIELDS:
            differ, vanilla = differing(base, other, field, keys), differing(*control, field, keys)
            line = f"- {field}: {differ} chunks differ (vanilla, the same way: {vanilla})"
            if ring:
                line += (f"; at the edge {differing(base, other, field, edge)} "
                         f"(vanilla: {differing(*control, field, edge)})")
            report.append(line)
            # A save loaded and saved again is not byte for byte the save it came from, in vanilla too (pending
            # ticks count from each run's own clock, palettes come back in another order): there the save only
            # has to differ no more than vanilla's own does.
            ok &= differ == 0 or (field == "saved" and ring > 0 and vanilla > 0)
        report.append("")

    report.append(f"- {runs['rust'][1].replace(chr(9), ', ')}")
    report.append("")
    compare("Same saved world, edited, lit and saved: vanilla's memory against Rust's", "vanilla", "rust",
            ("vanilla", "vanilla-again"), 0)
    compare("World generated on Rust's memory, loaded back by vanilla", "rust-generated", "vanilla-reads-rust",
            ("generated", "vanilla-reads-vanilla"), 2)
    for slug in ("rust", "rust-generated"):
        report.append(f"### Horizon on the live world ({slug} run)")
        report.append("")
        for prefix in ("sections", "light layers", "heightmaps", "horizon reads", "horizon writes", "registry"):
            for line in runs[slug]:
                if line.startswith(prefix + "\t"):
                    report.append(f"- {line.replace(chr(9), ': ', 1).replace(chr(9), ', ')}")
                    if prefix in ("horizon reads", "horizon writes", "registry"):
                        ok &= all(p.endswith(" 0") for p in line.split("\t") if "wrong" in p)
                    if prefix in ("sections", "light layers"):
                        ok &= "other 0" in line
        report.append("")
    if not facts:
        report.append(f"- registry: not checked (no facts.json in {args.horizon_resources})")
    return ok, report


def light(args, java: str, lib: Path, jar: Path, cp: list[Path], out: Path) -> tuple[bool, list[str]]:
    """P4: from one saved world, the probe's edits lit by vanilla's light engine (twice: the control) and by Rust's
    propagation, both on Rust's memory (P2), compared chunk by chunk; and the time each spent propagating."""
    run = world(args, java, lib, jar, cp, out)
    report = ["## Light: vanilla's engine against Rust's propagation", ""]
    plan = [("light-java", "rust", 40_000, "generated", ("-Dhz.light=time",)),
            ("light-java-again", "rust", 40_000, "generated", ("-Dhz.light=time",)),
            ("light-rust", "rust", 40_000, "generated", ("-Dhz.light=accel",))]
    if not (args.reuse and (out / "world-generated" / "universe").exists()):
        plan.insert(0, ("generated", "vanilla", 0, None, ()))
    runs = {}
    for slug, mode, edits, start_from, extra in plan:
        before = out / f"world-{slug}.tsv"
        if args.rust_only and slug != "light-rust" and before.exists():
            runs[slug] = read(before)
            continue
        got = run(slug, mode, edits, start_from, extra)
        if isinstance(got, str):
            return False, report + [f"- {got}"]
        runs[slug] = got
    a, b, c = (chunk_fields(runs[k]) for k in ("light-java", "light-rust", "light-java-again"))
    ok = len(a) == len(b) > 0 and set(a) == set(b)
    report.append(f"- chunks: {len(a)} / {len(b)}")
    for field in FIELDS:
        differ = sum(1 for k in a if a[k].get(field) != b.get(k, {}).get(field))
        control = sum(1 for k in a if a[k].get(field) != c.get(k, {}).get(field))
        report.append(f"- {field}: {differ} chunks differ (vanilla against itself: {control})")
        ok &= differ == 0 or (field == "saved" and differ <= control)
    report.append("")
    for slug in ("light-java", "light-java-again", "light-rust"):
        for line in runs[slug]:
            if line.startswith("light\t"):
                report.append(f"- {slug}: {line.replace(chr(9), ', ')}")
                if slug == "light-rust":
                    ok &= "java runs 0" in line
    return ok, report


if __name__ == "__main__":
    sys.exit(main())
