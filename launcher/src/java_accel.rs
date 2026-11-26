//! Horizon's accelerators in the real Minecraft: Java Edition (`docs/design/java-mods-everything.md`): what the
//! launcher adds to a Java instance's command when the player turns them on (`instance java --accel`).
//!
//! The agent (`horizon-accel.jar`, from `mods/horizon/src/accel/`) runs under any loader and edits the game as it
//! loads: `light` puts block storage and light in memory Rust owns and propagates light in Rust; `wgpu` draws through
//! Horizon's wgpu backend, the game's own backends after it. Each falls back to the game's own code where a loader or
//! mod changed what it would replace.
//!
//! Where it comes from ([`bundle`]), as `horizon-prepare`'s exporters: a Horizon release carries the agent's sources
//! (`mods/horizon/src/accel`) and its native libraries (`java-accel/`), and the launcher compiles the classes on the
//! player's machine, with the Java the instance runs on (Mojang's runtime has `javac`), against the player's own
//! game. Only Horizon's classes come out; the game stays theirs. The result is kept by a key of everything that went
//! in. `HORIZON_JAVA_ACCEL` names a ready folder instead (`tools/java_accel.py build`).
//!
//! The agent is built against one version of the game, so only that version takes it; any other, or a launcher
//! without the files, starts the game as it is.

use sha2::{Digest, Sha256};
use std::fs;
use std::io::Write;
use std::path::{Path, PathBuf};
use std::process::Command;

/// What the agent can do.
pub const KINDS: [&str; 2] = ["light", "wgpu"];
/// The versions the agent is built against.
pub const VERSIONS: [&str; 1] = ["26.3"];
/// The agent's jar, in [`dir`].
pub const JAR: &str = "horizon-accel.jar";

/// The accelerators a `--accel` value names: `light`, `wgpu`, both comma-separated, or `off`/empty for none.
pub fn parse(list: &str) -> Result<Vec<String>, String> {
    let list = list.trim();
    if list.is_empty() || list.eq_ignore_ascii_case("off") {
        return Ok(Vec::new());
    }
    let mut out = Vec::new();
    for kind in list.split(',').map(str::trim).filter(|k| !k.is_empty()) {
        let kind = kind.to_ascii_lowercase();
        if !KINDS.contains(&kind.as_str()) {
            return Err(format!(
                "{kind} is not an accelerator: {} or off",
                KINDS.join(", ")
            ));
        }
        if !out.contains(&kind) {
            out.push(kind);
        }
    }
    Ok(out)
}

/// A ready agent folder `HORIZON_JAVA_ACCEL` names (a developer's `tools/java_accel.py build`), if it holds the jar.
pub fn dir() -> Option<PathBuf> {
    let dir = PathBuf::from(std::env::var_os("HORIZON_JAVA_ACCEL")?);
    dir.join(JAR).is_file().then_some(dir)
}

/// The agent's native libraries, as a Horizon release carries them in `java-accel/` (`xtask package`).
pub const NATIVE: [&str; 2] = ["horizon_shm_probe", "horizon_wgpu_backend"];
/// Where a Horizon release keeps the agent's sources.
pub const SOURCES: &str = "mods/horizon/src/accel";

/// ASM (BSD-3-Clause), which the agent's edits use: the loader's own copy when it has one, else this one. The
/// checksums are of the jars Fabric's and NeoForge's installers put in `libraries/` (Maven Central's artifacts).
pub const ASM_VERSION: &str = "9.9.1";
pub const ASM: [(&str, &str); 2] = [
    (
        "asm",
        "6f3828a215c920059a5efa2fb55c233d6c54ec5cadca99ce1b1bdd10077c7ddd",
    ),
    (
        "asm-tree",
        "0f3555096b720b820bbacab0b515589bee0200bee099bda14c561738ae837ba1",
    ),
];

fn native_file(name: &str) -> String {
    if cfg!(windows) {
        format!("{name}.dll")
    } else if cfg!(target_os = "macos") {
        format!("lib{name}.dylib")
    } else {
        format!("lib{name}.so")
    }
}

/// The first of `roots` (installed Horizon versions, newest first) that carries the agent's sources and native
/// libraries.
pub fn release_root(roots: &[PathBuf]) -> Option<PathBuf> {
    roots
        .iter()
        .find(|r| {
            r.join(SOURCES).join("boot").is_dir()
                && NATIVE
                    .iter()
                    .all(|n| r.join("java-accel").join(native_file(n)).is_file())
        })
        .cloned()
}

/// Everything [`bundle`] needs.
pub struct Inputs<'a> {
    /// A Horizon release folder ([`release_root`]).
    pub root: &'a Path,
    /// The `java` the instance runs on; `javac` beside it compiles.
    pub java: &'a Path,
    /// The vanilla game's classpath (its libraries and client jar).
    pub classpath: &'a [PathBuf],
    /// The launcher's `libraries/` folder, where ASM is looked for and kept.
    pub libraries: &'a Path,
    /// Where compiled agents are kept, by key.
    pub cache: &'a Path,
}

/// A folder with the agent's jar, ASM and native libraries, made once per key: compiled from the release's sources
/// against the player's game.
pub fn bundle(
    inputs: &Inputs,
    fetch: &dyn Fn(&str) -> Result<Vec<u8>, String>,
    log: &dyn Fn(String),
) -> Result<PathBuf, String> {
    let sources = inputs.root.join(SOURCES);
    let asm = asm_jars(inputs.libraries, fetch, log)?;
    let key = key(inputs, &sources, &asm)?;
    let done = inputs.cache.join(&key);
    if fs::read_to_string(done.join("key")).is_ok_and(|k| k == key) {
        return Ok(done);
    }
    log("Compiling Horizon's accelerators for this game".into());
    let javac = javac(inputs.java)?;
    // Built aside and moved in whole: an interrupted build never looks done.
    let work = inputs.cache.join(format!(".{key}-{}", std::process::id()));
    let _ = fs::remove_dir_all(&work);
    let (boot, runtime) = (work.join("boot"), work.join("runtime"));
    mkdir(&boot)?;
    mkdir(&runtime)?;
    let sep = if cfg!(windows) { ";" } else { ":" };
    let join = |paths: &[&Path]| {
        paths
            .iter()
            .map(|p| p.to_string_lossy().into_owned())
            .collect::<Vec<_>>()
            .join(sep)
    };
    let asm_paths: Vec<&Path> = asm.iter().map(PathBuf::as_path).collect();
    compile(
        &javac,
        &join(&asm_paths),
        &boot,
        &java_files(&sources.join("boot"))?,
    )?;
    let mut cp: Vec<&Path> = vec![&boot];
    cp.extend(&asm_paths);
    cp.extend(inputs.classpath.iter().map(PathBuf::as_path));
    compile(
        &javac,
        &join(&cp),
        &runtime,
        &java_files(&sources.join("runtime"))?,
    )?;
    write_jar(&work.join(JAR), &boot, &runtime)?;
    for jar in &asm {
        copy(jar, &work.join(jar.file_name().unwrap_or_default()))?;
    }
    for name in NATIVE {
        let file = native_file(name);
        copy(
            &inputs.root.join("java-accel").join(&file),
            &work.join(&file),
        )?;
    }
    let _ = fs::remove_dir_all(&boot);
    let _ = fs::remove_dir_all(&runtime);
    fs::write(work.join("key"), &key).map_err(|e| e.to_string())?;
    let _ = fs::remove_dir_all(&done);
    fs::rename(&work, &done).map_err(|e| format!("{}: {e}", done.display()))?;
    Ok(done)
}

/// The key of a bundle: the sources, the native libraries, ASM and the game it compiles against.
fn key(inputs: &Inputs, sources: &Path, asm: &[PathBuf]) -> Result<String, String> {
    let mut hash = Sha256::new();
    hash.update(b"java-accel-v1 --release 25 -proc:none -encoding UTF-8\0");
    let mut files = java_files(sources)?;
    files.sort();
    for f in files {
        let data: Vec<u8> = fs::read(&f)
            .map_err(|e| format!("{}: {e}", f.display()))?
            .into_iter()
            .filter(|b| *b != b'\r')
            .collect();
        let name = f
            .strip_prefix(sources)
            .unwrap_or(&f)
            .to_string_lossy()
            .replace('\\', "/");
        hash.update(format!("{name}\0{}\0", data.len()));
        hash.update(data);
    }
    for name in NATIVE {
        let f = inputs.root.join("java-accel").join(native_file(name));
        hash.update(fs::read(&f).map_err(|e| format!("{}: {e}", f.display()))?);
    }
    for f in asm.iter().chain(inputs.classpath) {
        // The game's jars by name and size: the version folder names them, and a new game is a new file.
        let len = fs::metadata(f).map(|m| m.len()).unwrap_or(0);
        hash.update(format!(
            "{}\0{len}\0",
            f.file_name().unwrap_or_default().to_string_lossy()
        ));
    }
    Ok(hash
        .finalize()
        .iter()
        .take(16)
        .map(|b| format!("{b:02x}"))
        .collect())
}

/// ASM's jars in `libraries/` (Maven's layout), downloaded from Maven Central when missing, each checked.
fn asm_jars(
    libraries: &Path,
    fetch: &dyn Fn(&str) -> Result<Vec<u8>, String>,
    log: &dyn Fn(String),
) -> Result<Vec<PathBuf>, String> {
    let mut out = Vec::new();
    for (artifact, sha) in ASM {
        let relative = format!("org/ow2/asm/{artifact}/{ASM_VERSION}/{artifact}-{ASM_VERSION}.jar");
        let path = libraries.join(&relative);
        let good = |bytes: &[u8]| hex(&Sha256::digest(bytes)) == sha;
        if !fs::read(&path).is_ok_and(|b| good(&b)) {
            log(format!("Downloading ASM {ASM_VERSION} ({artifact})"));
            let bytes = fetch(&format!("https://repo1.maven.org/maven2/{relative}"))?;
            if !good(&bytes) {
                return Err(format!(
                    "{artifact}-{ASM_VERSION}.jar from Maven Central is not the one expected"
                ));
            }
            mkdir(path.parent().unwrap_or(libraries))?;
            fs::write(&path, bytes).map_err(|e| format!("{}: {e}", path.display()))?;
        }
        out.push(path);
    }
    Ok(out)
}

fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

/// `javac` beside `java`.
fn javac(java: &Path) -> Result<PathBuf, String> {
    let name = if cfg!(windows) { "javac.exe" } else { "javac" };
    let javac = java.with_file_name(name);
    if javac.is_file() {
        Ok(javac)
    } else {
        Err(format!(
            "The Java at {} has no javac to compile Horizon's accelerators with; the game starts without them",
            java.display()
        ))
    }
}

fn compile(javac: &Path, classpath: &str, out: &Path, files: &[PathBuf]) -> Result<(), String> {
    let mut cmd = Command::new(javac);
    crate::process::hide_console(&mut cmd);
    cmd.args([
        "--release",
        "25",
        "-proc:none",
        "-nowarn",
        "-encoding",
        "UTF-8",
        "-cp",
    ])
    .arg(classpath)
    .arg("-d")
    .arg(out)
    .args(files);
    let output = cmd
        .output()
        .map_err(|e| format!("{}: {e}", javac.display()))?;
    if !output.status.success() {
        let text = String::from_utf8_lossy(&output.stderr);
        return Err(format!(
            "Compiling Horizon's accelerators failed: {}",
            text.lines().take(5).collect::<Vec<_>>().join(" / ")
        ));
    }
    Ok(())
}

fn java_files(dir: &Path) -> Result<Vec<PathBuf>, String> {
    let mut out = Vec::new();
    for entry in fs::read_dir(dir).map_err(|e| format!("{}: {e}", dir.display()))? {
        let path = entry.map_err(|e| e.to_string())?.path();
        if path.is_dir() {
            out.extend(java_files(&path)?);
        } else if path.extension().is_some_and(|e| e == "java") {
            out.push(path);
        }
    }
    Ok(out)
}

fn class_files(dir: &Path, out: &mut Vec<PathBuf>) -> Result<(), String> {
    for entry in fs::read_dir(dir).map_err(|e| format!("{}: {e}", dir.display()))? {
        let path = entry.map_err(|e| e.to_string())?.path();
        if path.is_dir() {
            class_files(&path, out)?;
        } else if path.extension().is_some_and(|e| e == "class") {
            out.push(path);
        }
    }
    Ok(())
}

/// The agent's jar: the boot classes at its root, the runtime ones under `runtime/` (the agent defines those into
/// the game's own loader).
fn write_jar(jar: &Path, boot: &Path, runtime: &Path) -> Result<(), String> {
    let file = fs::File::create(jar).map_err(|e| format!("{}: {e}", jar.display()))?;
    let mut zip = zip::ZipWriter::new(file);
    let options = zip::write::SimpleFileOptions::default()
        .compression_method(zip::CompressionMethod::Deflated);
    let err = |e: zip::result::ZipError| e.to_string();
    zip.start_file("META-INF/MANIFEST.MF", options)
        .map_err(err)?;
    zip.write_all(
        b"Manifest-Version: 1.0\r\nPremain-Class: dev.horizonlab.accel.boot.Agent\r\n\r\n",
    )
    .map_err(|e| e.to_string())?;
    for (dir, prefix) in [(boot, ""), (runtime, "runtime/")] {
        let mut files = Vec::new();
        class_files(dir, &mut files)?;
        files.sort();
        for f in files {
            let name = f
                .strip_prefix(dir)
                .unwrap_or(&f)
                .to_string_lossy()
                .replace('\\', "/");
            zip.start_file(format!("{prefix}{name}"), options)
                .map_err(err)?;
            zip.write_all(&fs::read(&f).map_err(|e| e.to_string())?)
                .map_err(|e| e.to_string())?;
        }
    }
    zip.finish().map_err(err)?;
    Ok(())
}

fn mkdir(dir: &Path) -> Result<(), String> {
    fs::create_dir_all(dir).map_err(|e| format!("{}: {e}", dir.display()))
}

fn copy(from: &Path, to: &Path) -> Result<(), String> {
    fs::copy(from, to)
        .map(|_| ())
        .map_err(|e| format!("{}: {e}", from.display()))
}

/// The JVM arguments that start the game with the agent: none when `accel` is empty. An error says why the game
/// starts without it (its version is not one the agent knows, or the launcher has no agent).
pub fn jvm_args(
    accel: &[String],
    minecraft: &str,
    dir: Option<&Path>,
) -> Result<Vec<String>, String> {
    if accel.is_empty() {
        return Ok(Vec::new());
    }
    if !VERSIONS.contains(&minecraft) {
        return Err(format!(
            "Horizon's accelerators are for Minecraft {}; {minecraft} starts without them",
            VERSIONS.join(", ")
        ));
    }
    let dir = dir.ok_or(
        "This launcher has no Horizon accelerators (java-accel); the game starts without them",
    )?;
    Ok(vec![
        "--enable-native-access=ALL-UNNAMED".into(),
        format!("-javaagent:{}", dir.join(JAR).display()),
        format!("-Dhorizon.accel={}", accel.join(",")),
    ])
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parse_names_known_accelerators_once() {
        assert_eq!(parse("light,wgpu").unwrap(), ["light", "wgpu"]);
        assert_eq!(parse(" WGPU , light,wgpu ").unwrap(), ["wgpu", "light"]);
        assert!(parse("off").unwrap().is_empty());
        assert!(parse("").unwrap().is_empty());
        assert!(parse("light,turbo").is_err());
    }

    #[test]
    fn a_release_root_carries_sources_and_libraries() {
        let base = std::env::temp_dir().join(format!("hz-accel-root-{}", std::process::id()));
        let (old, new) = (base.join("old"), base.join("new"));
        fs::create_dir_all(old.join(SOURCES).join("boot")).unwrap();
        fs::create_dir_all(new.join("java-accel")).unwrap();
        fs::create_dir_all(new.join(SOURCES).join("boot")).unwrap();
        for n in NATIVE {
            fs::write(new.join("java-accel").join(native_file(n)), b"").unwrap();
        }
        // The newest release without the libraries is passed over.
        assert_eq!(release_root(&[old.clone(), new.clone()]), Some(new));
        assert_eq!(release_root(&[old]), None);
        fs::remove_dir_all(&base).unwrap();
    }

    /// Compiles the agent as a player's launcher does, from a release folder made of this checkout (its sources and
    /// the native libraries built beside the repository's target), with `HORIZON_TEST_JAVA` (a Java 25 with javac),
    /// against `HORIZON_TEST_CLASSPATH` (the 26.3 game's libraries and client jar), into `HORIZON_TEST_ACCEL_OUT`.
    #[test]
    #[ignore = "needs the player's own game and a JDK: see the doc comment"]
    fn bundle_compiles_against_the_players_game() {
        let var = |k: &str| std::env::var(k).unwrap_or_else(|_| panic!("{k}"));
        let repo = Path::new(env!("CARGO_MANIFEST_DIR")).join("..");
        let out = PathBuf::from(var("HORIZON_TEST_ACCEL_OUT"));
        let root = out.join("release");
        let _ = fs::remove_dir_all(&root);
        copy_tree(&repo.join(SOURCES), &root.join(SOURCES));
        fs::create_dir_all(root.join("java-accel")).unwrap();
        let built = PathBuf::from(var("HORIZON_TEST_NATIVE"));
        for n in NATIVE {
            fs::copy(
                built.join(native_file(n)),
                root.join("java-accel").join(native_file(n)),
            )
            .unwrap();
        }
        let sep = if cfg!(windows) { ';' } else { ':' };
        let classpath: Vec<PathBuf> = var("HORIZON_TEST_CLASSPATH")
            .split(sep)
            .map(PathBuf::from)
            .collect();
        let (java, libraries) = (var("HORIZON_TEST_JAVA"), var("HORIZON_TEST_LIBRARIES"));
        let inputs = Inputs {
            root: &root,
            java: Path::new(&java),
            classpath: &classpath,
            libraries: Path::new(&libraries),
            cache: &out.join("cache"),
        };
        let none = |url: &str| -> Result<Vec<u8>, String> {
            Err(format!("no download in this test: {url}"))
        };
        let dir = bundle(&inputs, &none, &|line| eprintln!("{line}")).unwrap();
        for f in [
            JAR.to_string(),
            native_file(NATIVE[0]),
            native_file(NATIVE[1]),
        ] {
            assert!(dir.join(&f).is_file(), "{f}");
        }
        // The second time it is the same folder, not compiled again.
        let again = bundle(&inputs, &none, &|line| panic!("compiled again: {line}")).unwrap();
        assert_eq!(again, dir);
        println!("bundle: {}", dir.display());
    }

    fn copy_tree(from: &Path, to: &Path) {
        fs::create_dir_all(to).unwrap();
        for e in fs::read_dir(from).unwrap() {
            let p = e.unwrap().path();
            let t = to.join(p.file_name().unwrap());
            if p.is_dir() {
                copy_tree(&p, &t);
            } else {
                fs::copy(&p, &t).unwrap();
            }
        }
    }

    #[test]
    fn jvm_args_only_for_a_known_version_with_the_files() {
        let on = vec!["light".to_string()];
        let dir = Path::new("x");
        assert!(jvm_args(&[], "1.21.4", None).unwrap().is_empty());
        assert!(jvm_args(&on, "1.21.4", Some(dir)).is_err());
        assert!(jvm_args(&on, "26.3", None).is_err());
        let args = jvm_args(&on, "26.3", Some(dir)).unwrap();
        assert_eq!(args[0], "--enable-native-access=ALL-UNNAMED");
        assert!(args[1].starts_with("-javaagent:") && args[1].ends_with(JAR));
        assert_eq!(args[2], "-Dhorizon.accel=light");
    }
}
