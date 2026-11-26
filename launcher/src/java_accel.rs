//! Horizon's accelerators in the real Minecraft: Java Edition (`docs/design/java-mods-everything.md`): what the
//! launcher adds to a Java instance's command when the player turns them on (`instance java --accel`).
//!
//! The agent (`horizon-accel.jar`, built by `tools/java_accel.py build` from `mods/horizon/src/accel/`) runs under any
//! loader and edits the game as it loads: `light` puts block storage and light in memory Rust owns and propagates
//! light in Rust; `wgpu` draws through Horizon's wgpu backend, the game's own backends after it. Each falls back to the
//! game's own code where a loader or mod changed what it would replace.
//!
//! The agent is built against one version of the game, so only that version takes it; any other, or a launcher
//! without the files, starts the game as it is.

use std::path::{Path, PathBuf};

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

/// Where the agent's files are: `HORIZON_JAVA_ACCEL`, else `java-accel/` beside the launcher. None without the jar.
pub fn dir() -> Option<PathBuf> {
    let dir = std::env::var_os("HORIZON_JAVA_ACCEL")
        .map(PathBuf::from)
        .or_else(|| {
            std::env::current_exe()
                .ok()
                .and_then(|e| e.parent().map(|p| p.join("java-accel")))
        })?;
    dir.join(JAR).is_file().then_some(dir)
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
