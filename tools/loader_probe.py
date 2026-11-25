#!/usr/bin/env python3
"""P1 of docs/design/java-mods-everything.md: which vanilla methods a pack's mods change.

Boots a real loader (Fabric, from a launcher profile) with the loader-neutral agent in
mods/horizon/src/loader-probe/, which loads every vanilla class through the game's loader, compares each
method with the pristine jar and exits before the game starts. Then names the mod behind each
change and groups changes by the subsystems an accelerator would replace.

Runs in a scratch game folder: the player's saves, options and configs are never read or written.

    python tools/loader_probe.py                       # the "Fabric 26.3" profile, the launcher's mods folder
    python tools/loader_probe.py --mods DIR --profile "Fabric 26.3" --out DIR
"""

from __future__ import annotations

import argparse
import collections
import io
import json
import os
import platform
import shutil
import subprocess
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "mods" / "horizon" / "src" / "loader-probe"

# Package prefixes of the subsystems the design accelerates first, then the rest.
SUBSYSTEMS = [
    ("light", ("net/minecraft/world/level/lighting/",)),
    ("chunk meshing + terrain draw", ("net/minecraft/client/renderer/chunk/", "net/minecraft/client/renderer/LevelRenderer",
                                      "net/minecraft/client/renderer/SectionOcclusionGraph")),
    ("worldgen", ("net/minecraft/world/level/levelgen/", "net/minecraft/world/level/chunk/status/",
                  "net/minecraft/world/level/biome/")),
    ("chunk storage", ("net/minecraft/world/level/chunk/",)),
    ("collision + shapes", ("net/minecraft/world/phys/",)),
    ("pathfinding", ("net/minecraft/world/level/pathfinder/",)),
    ("redstone", ("net/minecraft/world/level/redstone/",)),
    ("entity AI", ("net/minecraft/world/entity/ai/",)),
    ("entities", ("net/minecraft/world/entity/",)),
    ("blocks", ("net/minecraft/world/level/block/",)),
    ("server loop + levels", ("net/minecraft/server/",)),
    ("network", ("net/minecraft/network/",)),
    ("rendering (other)", ("net/minecraft/client/renderer/", "com/mojang/blaze3d/")),
    ("client UI", ("net/minecraft/client/gui/",)),
    ("client (other)", ("net/minecraft/client/",)),
]


def subsystem(cls: str) -> str:
    for name, prefixes in SUBSYSTEMS:
        if cls.startswith(prefixes):
            return name
    return "other"


def native_classifier() -> str:
    arm = platform.machine().lower() in ("arm64", "aarch64")
    base = "natives-windows" if os.name == "nt" else "natives-macos" if sys.platform == "darwin" else "natives-linux"
    return base + ("-arm64" if arm else "")


def is_native(lib: dict) -> bool:
    parts = lib.get("name", "").split(":")
    return len(parts) >= 4 and parts[3].startswith("natives")


def library_path(lib: dict, libraries: Path, natives: bool = False) -> Path | None:
    """A launcher library entry's jar, or None for natives (unless asked) and entries without a jar."""
    name = lib.get("name", "")
    parts = name.split(":")
    if is_native(lib) and not natives:
        return None
    artifact = lib.get("downloads", {}).get("artifact")
    if artifact and artifact.get("path"):
        return libraries / artifact["path"]
    if len(parts) < 3:
        return None
    group, art, version = parts[:3]
    classifier = f"-{parts[3]}" if len(parts) > 3 else ""
    return libraries / group.replace(".", "/") / art / version / f"{art}-{version}{classifier}.jar"


def allowed(lib: dict) -> bool:
    rules = lib.get("rules")
    if not rules:
        return True
    ok = False
    for rule in rules:
        os_name = rule.get("os", {}).get("name")
        if os_name is None or os_name == "windows" and os.name == "nt" or os_name == "linux" and sys.platform.startswith("linux") \
                or os_name == "osx" and sys.platform == "darwin":
            ok = rule.get("action") == "allow"
    return ok


def native_jars(profile_json: Path, versions: Path, libraries: Path) -> list[Path]:
    """This platform's native jars (LWJGL's), which some mods' pre-launch code loads before the game starts."""
    data = json.loads(profile_json.read_text(encoding="utf-8"))
    jars = []
    parent = data.get("inheritsFrom")
    if parent:
        jars += native_jars(versions / parent / f"{parent}.json", versions, libraries)
    want = native_classifier()
    for lib in data.get("libraries", []):
        if is_native(lib) and lib["name"].split(":")[3] == want:
            path = library_path(lib, libraries, natives=True)
            if path:
                jars.append(path)
    return jars


def extract_natives(jars: list[Path], folder: Path) -> None:
    shutil.rmtree(folder, ignore_errors=True)
    folder.mkdir(parents=True)
    for jar in jars:
        with zipfile.ZipFile(jar) as zf:
            for name in zf.namelist():
                if name.endswith((".dll", ".so", ".dylib")) and not name.startswith("META-INF/"):
                    (folder / Path(name).name).write_bytes(zf.read(name))


def library_key(lib: dict) -> str:
    """group:artifact[:classifier]: a profile's entry replaces its parent's with the same key, as launchers do."""
    parts = lib.get("name", "").split(":")
    return ":".join(parts[:2] + parts[3:4])


def profile_libraries(profile_json: Path, versions: Path, libraries: Path) -> dict[str, Path]:
    """group:artifact[:classifier] -> jar, parents first; a child's entry replaces its parent's with the same key."""
    data = json.loads(profile_json.read_text(encoding="utf-8"))
    parent = data.get("inheritsFrom")
    libs = profile_libraries(versions / parent / f"{parent}.json", versions, libraries) if parent else {}
    for lib in data.get("libraries", []):
        path = library_path(lib, libraries)
        if path is not None and allowed(lib):
            libs[library_key(lib)] = path
    return libs


def classpath(profile_json: Path, versions: Path, libraries: Path) -> tuple[list[Path], str, Path]:
    """The profile's libraries, its main class and the game jar a launcher puts on the classpath."""
    data = json.loads(profile_json.read_text(encoding="utf-8"))
    main, jar = data.get("mainClass"), versions / profile_json.parent.name / f"{profile_json.parent.name}.jar"
    parent = data.get("inheritsFrom")
    if parent:
        _, parent_main, parent_jar = classpath(versions / parent / f"{parent}.json", versions, libraries)
        main = main or parent_main
        if not jar.exists():
            jar = parent_jar
    return list(profile_libraries(profile_json, versions, libraries).values()), main, jar


def vanilla_jar(profile_json: Path, versions: Path) -> Path:
    """The pristine game jar: the root of the profile's inheritsFrom chain."""
    data = json.loads(profile_json.read_text(encoding="utf-8"))
    parent = data.get("inheritsFrom")
    if parent:
        return vanilla_jar(versions / parent / f"{parent}.json", versions)
    return versions / profile_json.parent.name / f"{profile_json.parent.name}.jar"


def profile_args(profile_json: Path, versions: Path, libraries: Path, kind: str) -> list[str]:
    """The loader profile's own plain-string JVM or game arguments (the vanilla parent's are the launcher's business)."""
    data = json.loads(profile_json.read_text(encoding="utf-8"))
    if not data.get("inheritsFrom"):
        return []
    values = {"${library_directory}": str(libraries), "${classpath_separator}": os.pathsep,
              "${version_name}": profile_json.parent.name}
    out = []
    for arg in data.get("arguments", {}).get(kind, []):
        if not isinstance(arg, str):
            continue
        for k, v in values.items():
            arg = arg.replace(k, v)
        out.append(arg)
    return out


def build(java_home: Path | None, asm: list[Path], out: Path) -> Path:
    javac = str(java_home / "bin" / "javac") if java_home else "javac"
    jar_tool = str(java_home / "bin" / "jar") if java_home else "jar"
    classes = out / "build" / "classes"
    shutil.rmtree(classes, ignore_errors=True)
    classes.mkdir(parents=True)
    sources = [str(p) for p in SRC.rglob("*.java")]
    subprocess.run([javac, "--release", "25", "-nowarn", "-cp", os.pathsep.join(map(str, asm)), "-d", str(classes), *sources],
                   check=True)
    manifest = out / "build" / "MANIFEST.MF"
    manifest.write_text("Premain-Class: dev.horizonlab.probe.Agent\n", encoding="utf-8")
    agent = out / "build" / "probe-agent.jar"
    agent.unlink(missing_ok=True)
    subprocess.run([jar_tool, "--create", "--file", str(agent), "--manifest", str(manifest), "-C", str(classes), "."], check=True)
    return agent


def mod_index(mods: Path) -> dict[str, str]:
    """Class name (dotted) -> the mod jar that holds it, nested jars included (Fabric's META-INF/jars, NeoForge's jarjar)."""
    index: dict[str, str] = {}

    def walk(zf: zipfile.ZipFile, label: str) -> None:
        for name in zf.namelist():
            if name.endswith(".class"):
                index.setdefault(name[:-6].replace("/", "."), label)
            elif name.endswith(".jar"):
                try:
                    with zipfile.ZipFile(io.BytesIO(zf.read(name))) as inner:
                        walk(inner, label)
                except zipfile.BadZipFile:
                    pass

    for jar in sorted(mods.glob("*.jar")):
        with zipfile.ZipFile(jar) as zf:
            walk(zf, jar.name)
    return index


def read_tsv(path: Path) -> list[dict[str, str]]:
    lines = path.read_text(encoding="utf-8").splitlines()
    head = lines[0].split("\t")
    return [dict(zip(head, line.split("\t"))) for line in lines[1:] if line]


def read_summary(out: Path) -> dict[str, str]:
    return dict(line.split("\t") for line in (out / "summary.tsv").read_text(encoding="utf-8").splitlines() if line)


def table(lines: list[str], title: str, counts: collections.Counter[str]) -> None:
    lines += ["", f"## {title}", "", "| by | methods |", "|---|---:|"]
    lines += [f"| {m} | {n} |" for m, n in counts.most_common()]


def subsystem_table(lines: list[str], title: str, by_sub: collections.Counter[str],
                    sub_by: dict[str, collections.Counter[str]]) -> None:
    lines += ["", f"## {title}", "", "| subsystem | methods | by |", "|---|---:|---|"]
    for name, _ in SUBSYSTEMS + [("other", ())]:
        who = ", ".join(f"{m} ({c})" for m, c in sub_by[name].most_common(4))
        lines.append(f"| {name} | {by_sub.get(name, 0)} | {who or 'nothing: free to accelerate'} |")


def report(out: Path, mods: Path, baseline: Path | None) -> None:
    """vs vanilla: everything that runs differently from the jar. vs baseline: what the mods change on top of the loader."""
    index = mod_index(mods)

    def owners(mixins: str, fallback: str) -> set[str]:
        found = {index.get(m, "(unknown mixin: " + m + ")") for m in mixins.split(",") if m}
        return found or {fallback}

    lines = ["# Loader probe report", ""]
    lines += [f"- {k}: {v}" for k, v in read_summary(out).items()]

    by_mod: collections.Counter[str] = collections.Counter()
    by_sub: collections.Counter[str] = collections.Counter()
    sub_by: dict[str, collections.Counter[str]] = collections.defaultdict(collections.Counter)
    for row in read_tsv(out / "methods.tsv"):
        if row["status"] != "changed":
            continue
        sub = subsystem(row["class"])
        by_sub[sub] += 1
        for o in owners(row["mixins"], "(no mixin: the loader's own patch or recompile)"):
            by_mod[o] += 1
            sub_by[sub][o] += 1
    table(lines, "Changed against the vanilla jar, by mod", by_mod)
    subsystem_table(lines, "Changed against the vanilla jar, by subsystem", by_sub, sub_by)

    if baseline:
        base = {(r["class"], r["method"]): r["code"] for r in read_tsv(baseline / "code.tsv")}
        mod_by: collections.Counter[str] = collections.Counter()
        mod_sub: collections.Counter[str] = collections.Counter()
        mod_sub_by: dict[str, collections.Counter[str]] = collections.defaultdict(collections.Counter)
        changed = added = 0
        for row in read_tsv(out / "code.tsv"):
            before = base.get((row["class"], row["method"]))
            if before == row["code"]:
                continue
            if before is None:
                added += 1
                continue
            changed += 1
            sub = subsystem(row["class"])
            mod_sub[sub] += 1
            for o in owners(row["mixins"], "(no mixin: another transform)"):
                mod_by[o] += 1
                mod_sub_by[sub][o] += 1
        lines += ["", "# What the mods change on top of the loader (same loader, no mods)", "",
                  f"- methods changed by mods: {changed}", f"- methods mods added: {added}"]
        table(lines, "By mod", mod_by)
        subsystem_table(lines, "By subsystem (an accelerator is blocked where its methods changed)", mod_sub, mod_sub_by)

    text = "\n".join(lines) + "\n"
    (out / "report.md").write_text(text, encoding="utf-8")
    print(text)


def main() -> int:
    default_mc = Path(os.environ.get("APPDATA", Path.home())) / ".minecraft"
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--minecraft", type=Path, default=default_mc, help="the launcher's folder (versions/, libraries/)")
    ap.add_argument("--profile", default="Fabric 26.3", help="a profile under versions/")
    ap.add_argument("--mods", type=Path, help="mod jars to load (default: <minecraft>/mods); *.jar only")
    ap.add_argument("--out", type=Path, help="output folder (default: .local/loader-probe/<profile>)")
    ap.add_argument("--java-home", type=Path, help="a JDK 25 (default: java on PATH)")
    ap.add_argument("--libraries-from", type=Path,
                    help="where to find libraries missing from <minecraft>/libraries (default: the launcher's own, read only)")
    ap.add_argument("--no-baseline", action="store_true", help="skip the run of the loader without mods")
    ap.add_argument("--report-only", action="store_true", help="rebuild report.md from the last run")
    args = ap.parse_args()

    versions, libraries = args.minecraft / "versions", args.minecraft / "libraries"
    mods = args.mods or args.minecraft / "mods"
    out = (args.out or ROOT / ".local" / "loader-probe" / args.profile.replace(" ", "-")).resolve()
    base_out = out / "baseline"
    if args.report_only:
        report(out, out / "game" / "mods", base_out if (base_out / "code.tsv").exists() else None)
        return 0

    profile_json = versions / args.profile / f"{args.profile}.json"
    libs, main_class, game_jar = classpath(profile_json, versions, libraries)
    pristine = vanilla_jar(profile_json, versions)
    # A loader installed into its own folder leaves the vanilla libraries to the launcher: read them from its folder.
    fallback = (args.libraries_from or default_mc / "libraries").resolve()

    def found(p: Path) -> Path:
        if p.exists():
            return p
        alt = fallback / p.relative_to(libraries)
        return alt if alt.exists() else p

    libs = [found(p) for p in libs]
    missing = [p for p in libs if not p.exists()]
    if missing:
        print("missing libraries (start the profile once in the launcher):", *missing, sep="\n  ", file=sys.stderr)
        return 1
    if not game_jar.exists():
        print(f"missing game jar: {game_jar}", file=sys.stderr)
        return 1
    out.mkdir(parents=True, exist_ok=True)
    agent = build(args.java_home, [p for p in libs if p.name.startswith("asm-")], out)
    natives = out / "natives"
    extract_natives([p for p in map(found, native_jars(profile_json, versions, libraries)) if p.exists()], natives)
    java = str(args.java_home / "bin" / "java") if args.java_home else "java"

    def run(mod_jars: list[Path], where: Path) -> bool:
        game_dir = where / "game"
        shutil.rmtree(game_dir, ignore_errors=True)
        (game_dir / "mods").mkdir(parents=True)
        for jar in mod_jars:
            shutil.copy2(jar, game_dir / "mods" / jar.name)
        # NeoForge's loading window would open before the game's main; the probe exits before any window.
        (game_dir / "config").mkdir()
        (game_dir / "config" / "fml.toml").write_text("earlyWindowControl = false\n", encoding="utf-8")
        for name in ("methods.tsv", "added.tsv", "code.tsv", "failed.tsv", "summary.tsv", "report.md"):
            (where / name).unlink(missing_ok=True)
        # Headless: a loader's error window would wait for a click instead of exiting.
        cmd = [java, "-Xmx3g", "-Djava.awt.headless=true", f"-Djava.library.path={natives}",
               f"-Dorg.lwjgl.librarypath={natives}", f"-javaagent:{agent}={pristine}|{where}",
               f"-Dfabric.gameJarPath={game_jar}", *profile_args(profile_json, versions, libraries, "jvm"),
               "-cp", os.pathsep.join(map(str, [*libs, game_jar])), main_class,
               *profile_args(profile_json, versions, libraries, "game"),
               "--gameDir", str(game_dir), "--version", args.profile, "--accessToken", "0", "--username", "probe"]
        print(f"[probe] {len(libs)} libraries, {len(mod_jars)} mods, main {main_class} -> {where}")
        with open(where / "run.log", "w", encoding="utf-8") as log:
            try:
                result = subprocess.run(cmd, cwd=game_dir, stdout=log, stderr=subprocess.STDOUT, timeout=900)
            except subprocess.TimeoutExpired:
                print(f"the loader ran past 15 minutes; see {where / 'run.log'}", file=sys.stderr)
                return False
        if not (where / "summary.tsv").exists():
            print(f"no report: the loader stopped before the game's main (exit {result.returncode}); see {where / 'run.log'}",
                  file=sys.stderr)
            return False
        return True

    if not args.no_baseline:
        base_out.mkdir(parents=True, exist_ok=True)
        if not run([], base_out):
            return 1
    if not run(sorted(mods.glob("*.jar")), out):
        return 1
    report(out, out / "game" / "mods", None if args.no_baseline else base_out)
    print(f"[probe] files in {out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())


