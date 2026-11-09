use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jstring};
use jni::JNIEnv;
use serde::{Deserialize, Serialize};

mod plan;

// ---------------------------------------------------------------------------
// Профили
// ---------------------------------------------------------------------------

#[derive(Debug, Clone, Serialize, Deserialize)]
pub enum Loader {
    Vanilla,
    NeoForge,
    Forge,
    Fabric,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct GameProfile {
    pub id: String,
    pub minecraft_version: String,
    pub loader: Loader,
    pub loader_version: Option<String>,
    pub java_major: u8,
    pub memory_mb: u32,
    pub render_distance: u8,
}

#[derive(Debug, thiserror::Error)]
pub enum ProfileError {
    #[error("profile id cannot be empty")]
    EmptyId,
    #[error("unsupported Java major version")]
    UnsupportedJava,
    #[error("memory allocation must be between 1024 and 8192 MB")]
    InvalidMemory,
    #[error("render distance must be between 2 and 64 chunks")]
    InvalidRenderDistance,
}

impl GameProfile {
    pub fn validate(&self) -> Result<(), ProfileError> {
        if self.id.trim().is_empty() {
            return Err(ProfileError::EmptyId);
        }
        if !(8..=25).contains(&self.java_major) {
            return Err(ProfileError::UnsupportedJava);
        }
        if !(1024..=8192).contains(&self.memory_mb) {
            return Err(ProfileError::InvalidMemory);
        }
        if !(2..=64).contains(&self.render_distance) {
            return Err(ProfileError::InvalidRenderDistance);
        }
        Ok(())
    }
}

// ---------------------------------------------------------------------------
// M1: манифест версий Mojang
//
// Сеть (скачивание version_manifest_v2.json) делает Kotlin-часть,
// а разбор и фильтрация живут здесь, в Rust, и тестируются без сети.
// ---------------------------------------------------------------------------

#[derive(Debug, Deserialize)]
struct RawManifest {
    versions: Vec<RawVersion>,
}

#[derive(Debug, Deserialize)]
struct RawVersion {
    id: String,
    #[serde(rename = "type")]
    kind: String,
    url: String,
}

#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
pub struct VersionEntry {
    pub id: String,
    pub kind: String,
    pub url: String,
}

#[derive(Debug, thiserror::Error)]
pub enum ManifestError {
    #[error("invalid version manifest: {0}")]
    Parse(#[from] serde_json::Error),
}

/// Разбирает version_manifest_v2.json и оставляет релизы
/// (и, по желанию, снапшоты). Порядок как у Mojang: новые версии первыми.
pub fn parse_versions(
    json: &str,
    include_snapshots: bool,
) -> Result<Vec<VersionEntry>, ManifestError> {
    let raw: RawManifest = serde_json::from_str(json)?;
    Ok(raw
        .versions
        .into_iter()
        .filter(|v| v.kind == "release" || (include_snapshots && v.kind == "snapshot"))
        .map(|v| VersionEntry {
            id: v.id,
            kind: v.kind,
            url: v.url,
        })
        .collect())
}

#[derive(Serialize)]
struct VersionsResponse {
    ok: bool,
    versions: Vec<VersionEntry>,
    error: Option<String>,
}

/// Ответ для Kotlin: {"ok":true,"versions":[{id,kind,url}...],"error":null}
/// или {"ok":false,"versions":[],"error":"..."}.
pub fn versions_response(json: &str, include_snapshots: bool) -> String {
    let response = match parse_versions(json, include_snapshots) {
        Ok(versions) => VersionsResponse {
            ok: true,
            versions,
            error: None,
        },
        Err(e) => VersionsResponse {
            ok: false,
            versions: Vec::new(),
            error: Some(e.to_string()),
        },
    };
    serde_json::to_string(&response)
        .unwrap_or_else(|_| r#"{"ok":false,"versions":[],"error":"serialize failed"}"#.to_string())
}

// ---------------------------------------------------------------------------
// JNI
// ---------------------------------------------------------------------------

#[no_mangle]
pub extern "system" fn Java_dev_rustcraft_launcher_RustBridge_coreVersion<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jstring {
    let value = env
        .new_string("RustCraft Core 0.2.0 (M2)")
        .expect("JNI string");
    value.into_raw()
}

#[no_mangle]
pub extern "system" fn Java_dev_rustcraft_launcher_RustBridge_parseVersions<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    json: JString<'local>,
    include_snapshots: jboolean,
) -> jstring {
    let input: String = match env.get_string(&json) {
        Ok(s) => s.into(),
        Err(_) => String::new(),
    };
    let output = versions_response(&input, include_snapshots != 0);
    env.new_string(output).expect("JNI string").into_raw()
}

fn run_json_call<'local>(
    env: &mut JNIEnv<'local>,
    json: &JString<'local>,
    handler: fn(&str) -> String,
) -> jstring {
    let input: String = match env.get_string(json) {
        Ok(s) => s.into(),
        Err(_) => String::new(),
    };
    let output = handler(&input);
    env.new_string(output).expect("JNI string").into_raw()
}

/// M2: JSON версии -> план скачивания (клиент, библиотеки, индекс ресурсов).
#[no_mangle]
pub extern "system" fn Java_dev_rustcraft_launcher_RustBridge_planVersion<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    json: JString<'local>,
) -> jstring {
    run_json_call(&mut env, &json, plan::plan_version_response)
}

/// M2: индекс ресурсов -> план скачивания ресурсов.
#[no_mangle]
pub extern "system" fn Java_dev_rustcraft_launcher_RustBridge_planAssets<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    json: JString<'local>,
) -> jstring {
    run_json_call(&mut env, &json, plan::plan_assets_response)
}

// ---------------------------------------------------------------------------
// Тесты
// ---------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn valid_profile_passes() {
        let profile = GameProfile {
            id: "my-neoforge-pack".into(),
            minecraft_version: "1.21.1".into(),
            loader: Loader::NeoForge,
            loader_version: Some("21.x".into()),
            java_major: 21,
            memory_mb: 4096,
            render_distance: 16,
        };
        assert!(profile.validate().is_ok());
    }

    #[test]
    fn rejects_too_much_memory() {
        let profile = GameProfile {
            id: "test".into(),
            minecraft_version: "1.21.1".into(),
            loader: Loader::Fabric,
            loader_version: None,
            java_major: 21,
            memory_mb: 12288,
            render_distance: 12,
        };
        assert!(matches!(profile.validate(), Err(ProfileError::InvalidMemory)));
    }

    const SAMPLE_MANIFEST: &str = r#"{
        "latest": {"release": "1.21.8", "snapshot": "25w31a"},
        "versions": [
            {"id": "25w31a", "type": "snapshot", "url": "https://example.com/25w31a.json",
             "time": "2025-08-01T00:00:00+00:00", "releaseTime": "2025-08-01T00:00:00+00:00",
             "sha1": "aaa", "complianceLevel": 1},
            {"id": "1.21.8", "type": "release", "url": "https://example.com/1.21.8.json",
             "time": "2025-07-17T00:00:00+00:00", "releaseTime": "2025-07-17T00:00:00+00:00",
             "sha1": "bbb", "complianceLevel": 1},
            {"id": "b1.7.3", "type": "old_beta", "url": "https://example.com/b1.7.3.json",
             "time": "2011-07-01T00:00:00+00:00", "releaseTime": "2011-07-01T00:00:00+00:00",
             "sha1": "ccc", "complianceLevel": 0},
            {"id": "1.20.1", "type": "release", "url": "https://example.com/1.20.1.json",
             "time": "2023-06-12T00:00:00+00:00", "releaseTime": "2023-06-12T00:00:00+00:00",
             "sha1": "ddd", "complianceLevel": 1}
        ]
    }"#;

    #[test]
    fn parses_only_releases_by_default() {
        let versions = parse_versions(SAMPLE_MANIFEST, false).unwrap();
        let ids: Vec<&str> = versions.iter().map(|v| v.id.as_str()).collect();
        assert_eq!(ids, vec!["1.21.8", "1.20.1"]);
    }

    #[test]
    fn can_include_snapshots_but_not_old_betas() {
        let versions = parse_versions(SAMPLE_MANIFEST, true).unwrap();
        let ids: Vec<&str> = versions.iter().map(|v| v.id.as_str()).collect();
        assert_eq!(ids, vec!["25w31a", "1.21.8", "1.20.1"]);
    }

    #[test]
    fn invalid_json_gives_error_response() {
        let out = versions_response("not json", false);
        let value: serde_json::Value = serde_json::from_str(&out).unwrap();
        assert_eq!(value["ok"], false);
        assert!(value["error"].as_str().unwrap().contains("invalid version manifest"));
    }

    #[test]
    fn ok_response_contains_versions() {
        let out = versions_response(SAMPLE_MANIFEST, false);
        let value: serde_json::Value = serde_json::from_str(&out).unwrap();
        assert_eq!(value["ok"], true);
        assert_eq!(value["versions"].as_array().unwrap().len(), 2);
        assert_eq!(value["versions"][0]["id"], "1.21.8");
    }
}
