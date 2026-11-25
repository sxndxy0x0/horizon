#!/usr/bin/env python3
"""P1 of docs/design/java-mods-everything.md: which vanilla methods a loader and a pack's mods change.

Boots a real loader from a launcher profile (Fabric, NeoForge, ...) twice, without mods and with
them, under the loader-neutral agent in mods/horizon/src/loader-probe/. The agent loads every
vanilla class through the game's loader, records each method as it will run and exits before the
game starts. The report compares the loader with the vanilla jar (by references, since NeoForge
recompiles the game) and the mods with the same loader, names the mod behind each change, and
groups changes by the subsystems an accelerator would replace.

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
import re
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


def zips(jar: Path):
    """(label, ZipFile) for a mod jar and every jar nested in it (Fabric's META-INF/jars, NeoForge's jarjar)."""
    def walk(zf: zipfile.ZipFile):
        yield zf
        for name in zf.namelist():
            if name.endswith(".jar"):
                try:
                    inner = zipfile.ZipFile(io.BytesIO(zf.read(name)))
                except zipfile.BadZipFile:
                    continue
                with inner:
                    yield from walk(inner)

    with zipfile.ZipFile(jar) as zf:
        yield from walk(zf)


def access_entries(zf: zipfile.ZipFile) -> list[tuple[str, str | None]]:
    """(class, member or None) for each entry of the jar's access wideners (Fabric, Quilt) and transformers (Forge)."""
    names = set(zf.namelist())
    files: list[tuple[str, str]] = []
    for meta, keys in (("fabric.mod.json", ("accessWidener",)), ("quilt.mod.json", ("access_widener",))):
        if meta in names:
            try:
                data = json.loads(zf.read(meta).decode("utf-8"))
            except (ValueError, UnicodeDecodeError):
                continue
            for key in keys:
                value = data.get(key)
                for path in value if isinstance(value, list) else [value] if value else []:
                    files.append(("aw", path))
    toml_paths = []
    for meta in ("META-INF/neoforge.mods.toml", "META-INF/mods.toml"):
        if meta in names:
            text = zf.read(meta).decode("utf-8", "replace")
            toml_paths += re.findall(r'\[\[accessTransformers\]\][^\[]*?file\s*=\s*"([^"]+)"', text)
    for path in toml_paths or ["META-INF/accesstransformer.cfg"]:
        files.append(("at", path))

    out: list[tuple[str, str | None]] = []
    for kind, path in files:
        if path not in names:
            continue
        for raw in zf.read(path).decode("utf-8", "replace").splitlines():
            line = raw.split("#", 1)[0].strip()
            if not line:
                continue
            parts = line.split()
            if kind == "aw":
                # accessible|extendable|mutable (transitive-...) class <c> | method <c> <name> <desc> | field <c> <name> <desc>
                if len(parts) >= 3 and parts[1] == "class":
                    out.append((parts[2], None))
                elif len(parts) >= 4 and parts[1] in ("method", "field"):
                    out.append((parts[2], parts[3]))
            elif len(parts) >= 2 and not parts[0].startswith("accessWidener"):
                # <access> <dotted class> [name[(desc)]]
                cls = parts[1].replace(".", "/")
                member = parts[2].split("(", 1)[0] if len(parts) >= 3 else None
                # `*` and `*()` widen every field or method: keyed as the whole class.
                out.append((cls, None if member == "*" else member))
    return out


def coremod_targets(zf: zipfile.ZipFile) -> set[str]:
    """The vanilla classes a jar's Forge coremods (META-INF/coremods.json -> JavaScript) name."""
    names = set(zf.namelist())
    if "META-INF/coremods.json" not in names:
        return set()
    try:
        scripts = json.loads(zf.read("META-INF/coremods.json").decode("utf-8")).values()
    except (ValueError, UnicodeDecodeError, AttributeError):
        return set()
    out = set()
    for script in scripts:
        if script in names:
            text = zf.read(script).decode("utf-8", "replace")
            out |= {c.replace(".", "/") for c in re.findall(r"(?:net|com)[./]mojang[./][\w./$]+|net[./]minecraft[./][\w./$]+", text)}
    return out


def mod_facts(mods: Path) -> tuple[dict[str, str], dict[tuple[str, str | None], set[str]], dict[str, set[str]]]:
    """Which mod jar holds each class (dotted), which mods widen each vanilla class or member, and which mods'
    coremods name each vanilla class."""
    classes: dict[str, str] = {}
    access: dict[tuple[str, str | None], set[str]] = collections.defaultdict(set)
    coremods: dict[str, set[str]] = collections.defaultdict(set)
    for jar in sorted(mods.glob("*.jar")):
        for zf in zips(jar):
            for name in zf.namelist():
                if name.endswith(".class"):
                    classes.setdefault(name[:-6].replace("/", "."), jar.name)
            for key in access_entries(zf):
                access[key].add(jar.name)
            for target in coremod_targets(zf):
                coremods[target].add(jar.name)
    return classes, access, coremods


def read_tsv(path: Path) -> list[dict[str, str]]:
    lines = path.read_text(encoding="utf-8").splitlines()
    head = lines[0].split("\t")
    return [dict(zip(head, line.split("\t"))) for line in lines[1:] if line]


def methods(path: Path) -> dict[tuple[str, str], dict[str, str]]:
    return {(r["class"], r["method"]): r for r in read_tsv(path)}


def read_summary(out: Path) -> dict[str, str]:
    return dict(line.split("\t") for line in (out / "summary.tsv").read_text(encoding="utf-8").splitlines() if line)


ACCESSOR = re.compile(r"access\$\d+\(")


def lambda_method(key: tuple[str, str]) -> bool:
    """A method whose name a recompile renumbers: a lambda body or a synthetic accessor (access$100). Compared
    only through the methods that call them."""
    return key[1].startswith("lambda$") or bool(ACCESSOR.match(key[1]))


ANONYMOUS = re.compile(r"\$\d+(?=$|\$)")


def anonymous(name: str) -> bool:
    return bool(ANONYMOUS.search(name))


def compare(before: dict, after: dict, recompiled: bool, out: dict[tuple[str, str], str]) -> None:
    for key, a in after.items():
        if recompiled and lambda_method(key):
            # lambda$m$0 before and after may be different lambdas.
            continue
        b = before.get(key)
        if b is None:
            out[key] = "added"
        elif b["code"] != a["code"]:
            out[key] = "recompile" if recompiled and b["refs"] == a["refs"] else "changed"
            if out[key] == "recompile" and b["access"] != a["access"]:
                out[key] = "access"
        elif b["access"] != a["access"]:
            out[key] = "access"
    for key in before:
        if key not in after and not (recompiled and lambda_method(key)):
            out[key] = "removed"


def anonymous_bags(rows: dict) -> dict[tuple, list[tuple[str, tuple]]]:
    """Anonymous classes grouped by their renumbered name and method names: (class, signature) per class, where the
    signature is each method's references and access, so classes a recompile renumbered still meet."""
    by_class: dict[str, list] = collections.defaultdict(list)
    for (cls, method), r in rows.items():
        # The constructor carries the captured values, which a recompile may capture differently.
        if anonymous(cls) and not lambda_method((cls, method)) and not method.startswith("<init>"):
            by_class[cls].append((method, r["refs"], r["access"]))
    bags: dict[tuple, list] = collections.defaultdict(list)
    for cls, entries in by_class.items():
        entries.sort()
        bags[(ANONYMOUS.sub("$#", cls), tuple(m for m, _, _ in entries))].append((cls, tuple(entries)))
    return bags


def classify(before: dict, after: dict, recompiled: bool) -> dict[tuple[str, str], str]:
    """Each method that differs: changed (code), access (flags only), added, removed.

    With recompiled, a method whose code differs but whose references match is "recompile" (a decompile-and-recompile
    moved it around), not "changed". Lambdas, whose synthetic names a recompile renumbers, are compared only by
    the methods that call them, and anonymous classes (renumbered too) are matched by their shape instead of their
    number: Outer$3 before may be Outer$5 after.
    """
    out: dict[tuple[str, str], str] = {}
    if not recompiled:
        compare(before, after, False, out)
        return out
    compare({k: v for k, v in before.items() if not anonymous(k[0])},
            {k: v for k, v in after.items() if not anonymous(k[0])}, True, out)
    bags_before, bags_after = anonymous_bags(before), anonymous_bags(after)
    for group in set(bags_before) | set(bags_after):
        left = collections.Counter(sig for _, sig in bags_before.get(group, []))
        unmatched = []
        for cls, sig in bags_after.get(group, []):
            if left[sig] > 0:
                left[sig] -= 1
            else:
                unmatched.append(cls)
        status = "changed" if group in bags_before else "added"
        for cls in unmatched:
            for method in group[1]:
                out[(cls, method)] = status
        # Classes before that nothing after matched (gone, or the before side of a changed one).
        for cls, sig in bags_before.get(group, []):
            if left[sig] > 0:
                left[sig] -= 1
                if not unmatched:
                    for method in group[1]:
                        out[(cls, method)] = "removed"
    return out

def table(lines: list[str], title: str, counts: collections.Counter[str]) -> None:
    lines += ["", f"### {title}", "", "| by | methods |", "|---|---:|"]
    lines += [f"| {m} | {n} |" for m, n in counts.most_common()]


def subsystem_table(lines: list[str], title: str, by_sub: collections.Counter[str],
                    sub_by: dict[str, collections.Counter[str]]) -> None:
    lines += ["", f"### {title}", "", "| subsystem | methods | by |", "|---|---:|---|"]
    for name, _ in SUBSYSTEMS + [("other", ())]:
        who = ", ".join(f"{m} ({c})" for m, c in sub_by[name].most_common(4))
        lines.append(f"| {name} | {by_sub.get(name, 0)} | {who or 'nothing: free to accelerate'} |")


def section(lines: list[str], title: str, status: dict[tuple[str, str], str], after: dict,
            owners) -> None:
    """Counts by status, then the changed methods by owner and by subsystem."""
    counts = collections.Counter(status.values())
    lines += ["", f"## {title}", ""]
    lines += [f"- {k}: {counts.get(k, 0)}" for k in ("changed", "recompile", "access", "added", "removed") if k in counts]
    by: collections.Counter[str] = collections.Counter()
    access_by: collections.Counter[str] = collections.Counter()
    by_sub: collections.Counter[str] = collections.Counter()
    sub_by: dict[str, collections.Counter[str]] = collections.defaultdict(collections.Counter)
    for key, s in status.items():
        if s == "changed":
            sub = subsystem(key[0])
            by_sub[sub] += 1
            for o in owners(key, after.get(key, {}).get("mixins", ""), s):
                by[o] += 1
                sub_by[sub][o] += 1
        elif s == "access":
            # A mixin may widen too: Mixin upgrades a member to the access its mixin declares.
            for o in owners(key, after.get(key, {}).get("mixins", ""), s):
                access_by[o] += 1
    table(lines, "Code changed, by who", by)
    if access_by:
        table(lines, "Access changed, by who", access_by)
    subsystem_table(lines, "Code changed, by subsystem (an accelerator is blocked where its methods changed)", by_sub, sub_by)


def patch_lines(text: str) -> set[int]:
    """The lines a unified diff changes, numbered in the new file: each added line, and both sides of a removal.

    NeoForge's patches leave the new side's start out (`@@ -250,11 +_,14 @@`): it is the old start moved by
    what the earlier hunks added and removed."""
    changed: set[int] = set()
    line = 0
    offset = 0
    in_hunk = False
    for raw in text.splitlines():
        m = re.match(r"@@ -(\d+)(?:,\d+)? \+(\d+|_)(?:,\d+)? @@", raw)
        if m:
            line = int(m.group(1)) + offset if m.group(2) == "_" else int(m.group(2))
            in_hunk = True
        elif not in_hunk or raw.startswith("\\"):
            continue
        elif raw.startswith("+"):
            changed.add(line)
            line += 1
            offset += 1
        elif raw.startswith("-"):
            changed.update((line - 1, line))
            offset -= 1
        else:
            line += 1
    return changed


def read_patches(patches: Path) -> dict[str, set[int] | None]:
    """The classes a loader's source patches name, with the lines each changes when the patch files themselves are
    given (a folder of patches/<class>.java.patch), or without lines for a list of their paths."""
    out: dict[str, set[int] | None] = {}
    if patches.is_dir():
        for f in patches.rglob("*.java.patch"):
            rel = f.relative_to(patches).as_posix().removeprefix("patches/")
            out[rel[: -len(".java.patch")]] = patch_lines(f.read_text(encoding="utf-8", errors="replace"))
        return out
    for line in patches.read_text(encoding="utf-8-sig").splitlines():
        line = line.strip()
        if line.endswith(".java.patch"):
            out[line.removeprefix("patches/")[: -len(".java.patch")]] = None
    return out


def top_class(name: str) -> str:
    return name.split("$", 1)[0]


def line_range(row: dict[str, str] | None) -> tuple[int, int] | None:
    text = (row or {}).get("lines", "")
    if not text:
        return None
    lo, hi = text.split("-")
    return int(lo), int(hi)


def settle(status: dict[tuple[str, str], str], patched: dict[str, set[int] | None],
           disk: dict[tuple[str, str], dict[str, str]] | None = None) -> tuple[dict[tuple[str, str], str], int]:
    """Decide the recompile's leftovers with the loader's source patches. The loader's jar is the recompiled game
    plus those patches, compiled with line numbers, so: a method in a class no patch names is the recompile only;
    with the patch files, a method of a patched class is changed exactly when the patch touches its lines (a
    patch that only changes a constant or a comparison counts, a recompiled neighbour does not), and patched
    lines outside every method (an annotation, a field, a signature) mark the class itself. Returns the settled
    statuses and how many methods the patches settled."""
    settled = 0
    out = dict(status)
    ranges: dict[str, list[tuple[tuple[str, str], tuple[int, int]]]] = collections.defaultdict(list)
    for key, row in (disk or {}).items():
        r = line_range(row)
        if r:
            ranges[top_class(key[0])].append((key, r))
    for key, s in status.items():
        cls = top_class(key[0])
        lines = patched.get(cls, ())
        if cls not in patched:
            if s == "changed":
                out[key] = "recompile"
                settled += 1
        elif lines is not None and s in ("changed", "recompile") and key[1] != "<class>":
            r = line_range((disk or {}).get(key))
            if r:
                hit = any(r[0] <= n <= r[1] for n in lines)
                if (s == "changed") != hit:
                    settled += 1
                out[key] = "changed" if hit else "recompile"
    for cls, lines in patched.items():
        if not lines:
            continue
        covered = {n for _, (lo, hi) in ranges.get(cls, []) for n in lines if lo <= n <= hi}
        if lines - covered and out.get((cls, "<class>"), "recompile") == "recompile":
            out[(cls, "<class>")] = "changed"
    return out, settled

def check_patches(lines: list[str], status: dict[tuple[str, str], str], patched: set[str], settled: int) -> None:
    """Compare the classes the probe finds the loader's jar changed with the loader's source patch files."""
    # A patch may only widen access, add a method or drop one, as well as change code.
    kinds: dict[str, set[str]] = collections.defaultdict(set)
    for k, s in status.items():
        if s != "recompile":
            kinds[top_class(k[0])].add(s)
    found = set(kinds)
    both = found & patched
    only = found - patched
    # The access transformer the installer applies to the jar widens classes no source patch names.
    access_only = {c for c in only if kinds[c] == {"access"}}
    other = only - access_only
    lines += ["", "### Against the loader's source patches", "",
              f"- source patch files: {len(patched)}",
              f"- methods the source patches settled against the references: {settled}",
              f"- classes the probe finds changed: {len(found)}",
              f"- in both: {len(both)} ({100 * len(both) / max(1, len(patched)):.1f}% of the patches)",
              f"- no patch, access only (the loader's access transformer): {len(access_only)}",
              f"- no patch, methods added or removed: {len(other)}"
              + (f" ({', '.join(sorted(other)[:10])})" if other else ""),
              f"- patches the probe finds nothing in: {len(patched - found)}"
              + (f" ({', '.join(sorted(patched - found)[:10])})" if patched - found else "")]

def attribute(key: tuple[str, str], mixins: str, status: str, fallback: str, classes: dict[str, str],
              access: dict[tuple[str, str | None], set[str]], coremods: dict[str, set[str]],
              vanilla: dict[tuple[str, str], dict[str, str]]) -> set[str]:
    """Who changed a method, most certain first: the mixins merged into it or using their members; the mods
    that widen it (access) or whose coremods name its class (code); the one mod all the mixins on its class
    come from; the loader, for a method the loader itself added; else the fallback."""
    entries = [m for m in mixins.split(",") if m and not m.startswith("?")]
    # A mixin, or ("@") an interface from outside the game the class implements: the mod whose jar holds it. An
    # interface no mod holds is the loader's or a library's (NeoForge's IKeyMappingExtension), not evidence.
    named = {classes.get(m, "(not in a mod jar: " + m + ")") for m in entries if not m.startswith("@")}
    named |= {classes[m[1:]] for m in entries if m.startswith("@") and m[1:] in classes}
    if named:
        return named
    if status == "access":
        if key[1] == "<class>":
            # The class's or one of its fields' flags: any mod widening something of this class.
            widened = set().union(*(mods for (cls, _), mods in access.items() if cls == key[0]))
        else:
            widened = access.get((key[0], key[1].split("(", 1)[0])) or access.get((key[0], None))
        if widened:
            return set(widened)
    elif coremods.get(key[0]):
        return set(coremods[key[0]])
    candidates = {classes.get(m[1:], "(not in a mod jar: " + m[1:] + ")") for m in mixins.split(",")
                  if m.startswith("?")}
    if len(candidates) == 1:
        return candidates
    if candidates:
        return {"one of: " + ", ".join(sorted(candidates))}
    if key not in vanilla:
        # Not a vanilla method: the loader's own (Forge's enum create stubs, which its enum extender fills in).
        return {"(the loader, on a method it added)"}
    return {fallback}


def report(out: Path, mods: Path, baseline: Path | None, patches: Path | None = None) -> None:
    """The loader against the vanilla jar, then the mods against the same loader without mods."""
    classes, access, coremods = mod_facts(mods)
    vanilla = methods(out / "vanilla.tsv")
    final = methods(out / "code.tsv")
    base = methods(baseline / "code.tsv") if baseline else None

    def owners(key: tuple[str, str], mixins: str, status: str, loader_label: str) -> set[str]:
        return attribute(key, mixins, status, loader_label, classes, access, coremods, vanilla)

    lines = ["# Loader probe report", ""]
    lines += [f"- {k}: {v}" for k, v in read_summary(out).items()]
    loader_dir = baseline if base is not None else out
    loader_rows = base if base is not None else final
    suffix = "" if base is not None else " (mods included: no baseline)"
    disk_file = loader_dir / "ondisk.tsv"
    if disk_file.exists():
        # The loader's jar as stored (its install-time patches, against vanilla), then what loading it changes
        # (runtime transforms, against that jar), each told apart instead of mixed.
        stored = methods(disk_file)
        # Whole classes: a class read from the loader's jar replaces the vanilla one, methods it dropped included.
        stored_classes = {k[0] for k in stored}
        disk = {k: v for k, v in vanilla.items() if k[0] not in stored_classes} | stored
        disk_status = classify(vanilla, disk, recompiled=True)
        patched = read_patches(patches) if patches else None
        if patched is not None:
            disk_status, settled = settle(disk_status, patched, disk)
        section(lines, "The loader's jar against the vanilla jar" + suffix, disk_status, disk,
                lambda k, m, s: owners(k, m, s, "(the loader's jar)"))
        if patched is not None:
            check_patches(lines, disk_status, set(patched), settled)
        section(lines, "What the loader changes while loading" + suffix, classify(disk, loader_rows, recompiled=False),
                loader_rows, lambda k, m, s: owners(k, m, s, "(the loader at runtime)"))
    else:
        loader_status = classify(vanilla, loader_rows, recompiled=True)
        section(lines, "The loader against the vanilla jar" + suffix, loader_status, loader_rows,
                lambda k, m, s: owners(k, m, s, "(the loader)"))
        if patches:
            check_patches(lines, loader_status, set(read_patches(patches)), 0)
    if base is not None:
        mod_status = classify(base, final, recompiled=False)
        section(lines, "The mods against the same loader without mods", mod_status, final,
                lambda k, m, s: owners(k, m, s, "(unattributed)"))
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
    ap.add_argument("--loader-patches", type=Path,
                    help="the loader's source patches: a folder of patches/<class>.java.patch files (decides each method "
                         "of a recompiled jar by the lines its patch touches), or a list of their paths (by class)")
    ap.add_argument("--dump", default="", help="classes to write as the game runs them, to <out>/dump (a/B,c/D)")
    ap.add_argument("--report-only", action="store_true", help="rebuild report.md from the last run")
    args = ap.parse_args()

    # Absolute: the JVM runs in the scratch game folder.
    args.minecraft = args.minecraft.resolve()
    if args.mods:
        args.mods = args.mods.resolve()
    versions, libraries = args.minecraft / "versions", args.minecraft / "libraries"
    mods = args.mods or args.minecraft / "mods"
    out = (args.out or ROOT / ".local" / "loader-probe" / args.profile.replace(" ", "-")).resolve()
    base_out = out / "baseline"
    if args.report_only:
        report(out, out / "game" / "mods", base_out if (base_out / "code.tsv").exists() else None, args.loader_patches)
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
        for name in ("vanilla.tsv", "ondisk.tsv", "code.tsv", "failed.tsv", "summary.tsv", "report.md"):
            (where / name).unlink(missing_ok=True)
        # Headless: a loader's error window would wait for a click instead of exiting.
        cmd = [java, "-Xmx3g", "-Djava.awt.headless=true", f"-Dprobe.dump={args.dump}", f"-Djava.library.path={natives}",
               f"-Dorg.lwjgl.librarypath={natives}", f"-javaagent:{agent}={pristine}|{where}|{game_dir / 'mods'}",
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
    report(out, out / "game" / "mods", None if args.no_baseline else base_out, args.loader_patches)
    print(f"[probe] files in {out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())


