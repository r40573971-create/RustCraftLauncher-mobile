//! M2: план скачивания игры.
//!
//! Сеть делает Kotlin-часть. Здесь только разбор JSON версии и индекса ресурсов
//! и проверка того, что пути и ссылки безопасны. Результат: список файлов
//! (url, относительный путь, sha1, размер), которые нужно скачать.

use serde::{Deserialize, Serialize};
use std::collections::{BTreeMap, HashMap, HashSet};

const RESOURCES_URL: &str = "https://resources.download.minecraft.net";

// ---------------------------------------------------------------------------
// Общие типы
// ---------------------------------------------------------------------------

#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
pub struct FileEntry {
    pub url: String,
    pub path: String,
    pub sha1: String,
    pub size: u64,
    pub kind: String,
}

#[derive(Debug, thiserror::Error)]
pub enum PlanError {
    #[error("invalid JSON: {0}")]
    Parse(#[from] serde_json::Error),
    #[error("unsafe or invalid path: {0}")]
    BadPath(String),
    #[error("invalid download URL: {0}")]
    BadUrl(String),
    #[error("invalid sha1 for {0}")]
    BadHash(String),
}

/// Относительный путь без `..`, абсолютных путей, обратных слэшей и двоеточий.
fn is_safe_relative_path(path: &str) -> bool {
    !path.is_empty()
        && !path.starts_with('/')
        && !path.contains('\\')
        && !path.contains(':')
        && path
            .split('/')
            .all(|part| !part.is_empty() && part != "." && part != "..")
}

/// Один компонент пути (без слэшей), например id версии.
fn is_safe_component(value: &str) -> bool {
    is_safe_relative_path(value) && !value.contains('/')
}

fn is_sha1(value: &str) -> bool {
    value.len() == 40 && value.bytes().all(|b| b.is_ascii_hexdigit())
}

fn make_entry(
    url: &str,
    path: String,
    sha1: &str,
    size: u64,
    kind: &str,
) -> Result<FileEntry, PlanError> {
    if !url.starts_with("https://") {
        return Err(PlanError::BadUrl(url.to_string()));
    }
    if !is_safe_relative_path(&path) {
        return Err(PlanError::BadPath(path));
    }
    if !is_sha1(sha1) {
        return Err(PlanError::BadHash(path));
    }
    Ok(FileEntry {
        url: url.to_string(),
        path,
        sha1: sha1.to_ascii_lowercase(),
        size,
        kind: kind.to_string(),
    })
}

fn to_response<T: Serialize>(result: Result<T, PlanError>) -> String {
    let value = match result {
        Ok(plan) => match serde_json::to_value(plan) {
            Ok(mut value) => {
                if let Some(object) = value.as_object_mut() {
                    object.insert("ok".to_string(), serde_json::Value::Bool(true));
                    object.insert("error".to_string(), serde_json::Value::Null);
                }
                value
            }
            Err(e) => serde_json::json!({ "ok": false, "error": e.to_string() }),
        },
        Err(e) => serde_json::json!({ "ok": false, "error": e.to_string() }),
    };
    value.to_string()
}

// ---------------------------------------------------------------------------
// JSON версии (piston-meta .../<version>.json)
// ---------------------------------------------------------------------------

#[derive(Debug, Deserialize)]
struct RawDownload {
    url: String,
    sha1: String,
    size: u64,
    path: Option<String>,
}

#[derive(Debug, Deserialize)]
struct RawAssetIndexRef {
    id: String,
    url: String,
    sha1: String,
    size: u64,
}

#[derive(Debug, Deserialize)]
struct RawDownloads {
    client: RawDownload,
}

#[derive(Debug, Deserialize)]
struct RawJavaVersion {
    #[serde(rename = "majorVersion")]
    major_version: u32,
}

#[derive(Debug, Deserialize)]
struct RawOs {
    name: Option<String>,
    arch: Option<String>,
}

#[derive(Debug, Deserialize)]
struct RawRule {
    action: String,
    os: Option<RawOs>,
}

#[derive(Debug, Deserialize)]
struct RawLibDownloads {
    artifact: Option<RawDownload>,
    classifiers: Option<HashMap<String, RawDownload>>,
}

#[derive(Debug, Deserialize)]
struct RawLibrary {
    downloads: Option<RawLibDownloads>,
    rules: Option<Vec<RawRule>>,
    natives: Option<HashMap<String, String>>,
}

#[derive(Debug, Deserialize)]
struct RawVersionJson {
    id: String,
    #[serde(rename = "mainClass")]
    main_class: String,
    #[serde(rename = "assetIndex")]
    asset_index: RawAssetIndexRef,
    downloads: RawDownloads,
    #[serde(rename = "javaVersion")]
    java_version: Option<RawJavaVersion>,
    #[serde(default)]
    libraries: Vec<RawLibrary>,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AssetIndexRef {
    pub id: String,
    pub url: String,
    pub sha1: String,
    pub size: u64,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct VersionPlan {
    pub id: String,
    pub main_class: String,
    pub java_major: u32,
    pub asset_index: AssetIndexRef,
    /// Размер клиента и библиотек. Ресурсы считаются отдельно (plan_assets).
    pub total_bytes: u64,
    pub files: Vec<FileEntry>,
}

/// Правила библиотек. Android-телефон для Mojang это Linux ARM64.
/// Нативные библиотеки LWJGL под настоящий Android будут пересмотрены в M3/M4.
fn rules_allow(rules: &Option<Vec<RawRule>>) -> bool {
    let rules = match rules {
        Some(rules) if !rules.is_empty() => rules,
        _ => return true,
    };
    let mut allowed = false;
    for rule in rules {
        let matches = match &rule.os {
            None => true,
            Some(os) => {
                os.name.as_deref().map_or(true, |name| name == "linux")
                    && os
                        .arch
                        .as_deref()
                        .map_or(true, |arch| arch == "arm64" || arch == "aarch64")
            }
        };
        if matches {
            allowed = rule.action == "allow";
        }
    }
    allowed
}

pub fn plan_version(json: &str) -> Result<VersionPlan, PlanError> {
    let raw: RawVersionJson = serde_json::from_str(json)?;

    if !is_safe_component(&raw.id) {
        return Err(PlanError::BadPath(raw.id));
    }
    if !is_safe_component(&raw.asset_index.id) {
        return Err(PlanError::BadPath(raw.asset_index.id));
    }

    let mut files: Vec<FileEntry> = Vec::new();

    files.push(make_entry(
        &raw.downloads.client.url,
        format!("versions/{0}/{0}.jar", raw.id),
        &raw.downloads.client.sha1,
        raw.downloads.client.size,
        "client",
    )?);
    files.push(make_entry(
        &raw.asset_index.url,
        format!("assets/indexes/{}.json", raw.asset_index.id),
        &raw.asset_index.sha1,
        raw.asset_index.size,
        "assetIndex",
    )?);

    for library in &raw.libraries {
        if !rules_allow(&library.rules) {
            continue;
        }
        let downloads = match &library.downloads {
            Some(downloads) => downloads,
            None => continue,
        };

        if let Some(artifact) = &downloads.artifact {
            if let Some(path) = &artifact.path {
                files.push(make_entry(
                    &artifact.url,
                    format!("libraries/{}", path),
                    &artifact.sha1,
                    artifact.size,
                    "library",
                )?);
            }
        }

        // Старый формат нативных библиотек (до 1.19): natives + classifiers.
        if let (Some(natives), Some(classifiers)) = (&library.natives, &downloads.classifiers) {
            if let Some(classifier) = natives.get("linux") {
                let key = classifier.replace("${arch}", "64");
                if let Some(native) = classifiers.get(&key) {
                    if let Some(path) = &native.path {
                        files.push(make_entry(
                            &native.url,
                            format!("libraries/{}", path),
                            &native.sha1,
                            native.size,
                            "native",
                        )?);
                    }
                }
            }
        }
    }

    let mut seen = HashSet::new();
    files.retain(|file| seen.insert(file.path.clone()));
    let total_bytes: u64 = files.iter().map(|file| file.size).sum();

    Ok(VersionPlan {
        id: raw.id,
        main_class: raw.main_class,
        java_major: raw.java_version.map_or(8, |java| java.major_version),
        asset_index: AssetIndexRef {
            id: raw.asset_index.id,
            url: raw.asset_index.url,
            sha1: raw.asset_index.sha1,
            size: raw.asset_index.size,
        },
        total_bytes,
        files,
    })
}

pub fn plan_version_response(json: &str) -> String {
    to_response(plan_version(json))
}

// ---------------------------------------------------------------------------
// Индекс ресурсов (assets/indexes/<id>.json)
// ---------------------------------------------------------------------------

#[derive(Debug, Deserialize)]
struct RawAssetObject {
    hash: String,
    size: u64,
}

#[derive(Debug, Deserialize)]
struct RawAssetIndexFile {
    objects: BTreeMap<String, RawAssetObject>,
    #[serde(rename = "virtual", default)]
    is_virtual: bool,
    #[serde(default)]
    map_to_resources: bool,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AssetsPlan {
    /// Старые версии (до 1.6) хранят ресурсы в другом виде, это отдельная задача.
    pub legacy: bool,
    pub total_bytes: u64,
    pub files: Vec<FileEntry>,
}

pub fn plan_assets(json: &str) -> Result<AssetsPlan, PlanError> {
    let raw: RawAssetIndexFile = serde_json::from_str(json)?;

    let mut seen = HashSet::new();
    let mut files = Vec::new();
    for object in raw.objects.values() {
        if !is_sha1(&object.hash) {
            return Err(PlanError::BadHash(object.hash.clone()));
        }
        let hash = object.hash.to_ascii_lowercase();
        if !seen.insert(hash.clone()) {
            continue;
        }
        let prefix = &hash[..2];
        files.push(make_entry(
            &format!("{}/{}/{}", RESOURCES_URL, prefix, hash),
            format!("assets/objects/{}/{}", prefix, hash),
            &hash,
            object.size,
            "asset",
        )?);
    }

    let total_bytes: u64 = files.iter().map(|file| file.size).sum();
    Ok(AssetsPlan {
        legacy: raw.is_virtual || raw.map_to_resources,
        total_bytes,
        files,
    })
}

pub fn plan_assets_response(json: &str) -> String {
    to_response(plan_assets(json))
}

// ---------------------------------------------------------------------------
// Тесты
// ---------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;

    const SHA_A: &str = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    const SHA_B: &str = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    const SHA_C: &str = "cccccccccccccccccccccccccccccccccccccccc";
    const SHA_D: &str = "dddddddddddddddddddddddddddddddddddddddd";

    fn sample_version() -> String {
        format!(
            r#"{{
            "id": "1.21.8",
            "mainClass": "net.minecraft.client.main.Main",
            "javaVersion": {{"component": "java-runtime-delta", "majorVersion": 21}},
            "assetIndex": {{"id": "17", "sha1": "{b}", "size": 100,
                            "totalSize": 999, "url": "https://example.com/17.json"}},
            "downloads": {{"client": {{"sha1": "{a}", "size": 1000, "url": "https://example.com/client.jar"}}}},
            "libraries": [
              {{"name": "com.example:plain:1.0",
                "downloads": {{"artifact": {{"path": "com/example/plain/1.0/plain-1.0.jar",
                    "sha1": "{c}", "size": 10, "url": "https://example.com/plain.jar"}}}}}},
              {{"name": "org.lwjgl:lwjgl:3.3.3:natives-macos",
                "downloads": {{"artifact": {{"path": "org/lwjgl/lwjgl/3.3.3/lwjgl-natives-macos.jar",
                    "sha1": "{c}", "size": 20, "url": "https://example.com/mac.jar"}}}},
                "rules": [{{"action": "allow", "os": {{"name": "osx"}}}}]}},
              {{"name": "org.lwjgl:lwjgl:3.3.3:natives-linux-arm64",
                "downloads": {{"artifact": {{"path": "org/lwjgl/lwjgl/3.3.3/lwjgl-natives-linux-arm64.jar",
                    "sha1": "{d}", "size": 30, "url": "https://example.com/arm.jar"}}}},
                "rules": [{{"action": "allow", "os": {{"name": "linux", "arch": "arm64"}}}}]}},
              {{"name": "org.lwjgl:lwjgl:3.3.3:natives-linux-x86",
                "downloads": {{"artifact": {{"path": "org/lwjgl/lwjgl/3.3.3/lwjgl-natives-linux-x86.jar",
                    "sha1": "{d}", "size": 40, "url": "https://example.com/x86.jar"}}}},
                "rules": [{{"action": "allow", "os": {{"name": "linux", "arch": "x86"}}}}]}},
              {{"name": "no.windows:lib:1",
                "downloads": {{"artifact": {{"path": "no/windows/lib/1/lib-1.jar",
                    "sha1": "{c}", "size": 50, "url": "https://example.com/lib.jar"}}}},
                "rules": [{{"action": "allow"}}, {{"action": "disallow", "os": {{"name": "windows"}}}}]}},
              {{"name": "old:native:1",
                "downloads": {{
                  "artifact": {{"path": "old/native/1/native-1.jar", "sha1": "{c}", "size": 5,
                                "url": "https://example.com/old.jar"}},
                  "classifiers": {{"natives-linux": {{"path": "old/native/1/native-1-natives-linux.jar",
                                "sha1": "{d}", "size": 6, "url": "https://example.com/old-linux.jar"}}}}}},
                "natives": {{"linux": "natives-linux", "osx": "natives-osx"}}}},
              {{"name": "plain:duplicate:1.0",
                "downloads": {{"artifact": {{"path": "com/example/plain/1.0/plain-1.0.jar",
                    "sha1": "{c}", "size": 10, "url": "https://example.com/plain.jar"}}}}}}
            ]
        }}"#,
            a = SHA_A,
            b = SHA_B,
            c = SHA_C,
            d = SHA_D
        )
    }

    #[test]
    fn plans_client_assets_index_and_allowed_libraries() {
        let plan = plan_version(&sample_version()).unwrap();
        assert_eq!(plan.id, "1.21.8");
        assert_eq!(plan.main_class, "net.minecraft.client.main.Main");
        assert_eq!(plan.java_major, 21);
        assert_eq!(plan.asset_index.id, "17");

        let paths: Vec<&str> = plan.files.iter().map(|f| f.path.as_str()).collect();
        assert!(paths.contains(&"versions/1.21.8/1.21.8.jar"));
        assert!(paths.contains(&"assets/indexes/17.json"));
        assert!(paths.contains(&"libraries/com/example/plain/1.0/plain-1.0.jar"));
        assert!(paths.contains(&"libraries/org/lwjgl/lwjgl/3.3.3/lwjgl-natives-linux-arm64.jar"));
        assert!(paths.contains(&"libraries/no/windows/lib/1/lib-1.jar"));
        assert!(paths.contains(&"libraries/old/native/1/native-1-natives-linux.jar"));
    }

    #[test]
    fn applies_rules_and_removes_duplicates() {
        let plan = plan_version(&sample_version()).unwrap();
        let paths: Vec<&str> = plan.files.iter().map(|f| f.path.as_str()).collect();
        assert!(!paths.iter().any(|p| p.contains("natives-macos")));
        assert!(!paths.iter().any(|p| p.contains("natives-linux-x86")));
        let plain = paths
            .iter()
            .filter(|p| **p == "libraries/com/example/plain/1.0/plain-1.0.jar")
            .count();
        assert_eq!(plain, 1);
    }

    #[test]
    fn total_bytes_matches_files() {
        let plan = plan_version(&sample_version()).unwrap();
        let sum: u64 = plan.files.iter().map(|f| f.size).sum();
        assert_eq!(plan.total_bytes, sum);
    }

    #[test]
    fn missing_java_version_defaults_to_8() {
        let json = sample_version().replace(
            r#""javaVersion": {"component": "java-runtime-delta", "majorVersion": 21},"#,
            "",
        );
        let plan = plan_version(&json).unwrap();
        assert_eq!(plan.java_major, 8);
    }

    #[test]
    fn rejects_path_traversal_in_library() {
        let json = sample_version().replace(
            "com/example/plain/1.0/plain-1.0.jar",
            "../../evil.jar",
        );
        assert!(matches!(plan_version(&json), Err(PlanError::BadPath(_))));
    }

    #[test]
    fn rejects_non_https_url() {
        let json = sample_version().replace("https://example.com/client.jar", "http://example.com/client.jar");
        assert!(matches!(plan_version(&json), Err(PlanError::BadUrl(_))));
    }

    #[test]
    fn rejects_unsafe_version_id() {
        let json = sample_version().replace(r#""id": "1.21.8""#, r#""id": "../x""#);
        assert!(matches!(plan_version(&json), Err(PlanError::BadPath(_))));
    }

    #[test]
    fn rejects_bad_sha1() {
        let json = sample_version().replace(SHA_A, "xyz");
        assert!(matches!(plan_version(&json), Err(PlanError::BadHash(_))));
    }

    #[test]
    fn invalid_json_gives_error_response() {
        let out = plan_version_response("{ nope");
        let value: serde_json::Value = serde_json::from_str(&out).unwrap();
        assert_eq!(value["ok"], false);
        assert!(value["error"].as_str().unwrap().contains("invalid JSON"));
    }

    #[test]
    fn ok_response_uses_camel_case() {
        let out = plan_version_response(&sample_version());
        let value: serde_json::Value = serde_json::from_str(&out).unwrap();
        assert_eq!(value["ok"], true);
        assert_eq!(value["mainClass"], "net.minecraft.client.main.Main");
        assert_eq!(value["javaMajor"], 21);
        assert_eq!(value["assetIndex"]["id"], "17");
        assert!(value["totalBytes"].as_u64().unwrap() > 0);
        assert!(value["files"].as_array().unwrap().len() >= 5);
    }

    fn sample_assets() -> String {
        format!(
            r#"{{"objects": {{
                "minecraft/lang/en_us.json": {{"hash": "{a}", "size": 11}},
                "minecraft/lang/copy.json": {{"hash": "{a}", "size": 11}},
                "minecraft/sounds/x.ogg": {{"hash": "{b}", "size": 22}}
            }}}}"#,
            a = SHA_A,
            b = SHA_B
        )
    }

    #[test]
    fn plans_assets_and_deduplicates_by_hash() {
        let plan = plan_assets(&sample_assets()).unwrap();
        assert_eq!(plan.files.len(), 2);
        assert_eq!(plan.total_bytes, 33);
        assert!(!plan.legacy);
        let first = &plan.files[0];
        assert_eq!(first.path, format!("assets/objects/aa/{}", SHA_A));
        assert_eq!(first.url, format!("{}/aa/{}", RESOURCES_URL, SHA_A));
        assert_eq!(first.kind, "asset");
    }

    #[test]
    fn detects_legacy_assets() {
        let json = format!(
            r#"{{"virtual": true, "objects": {{"a.txt": {{"hash": "{}", "size": 1}}}}}}"#,
            SHA_A
        );
        assert!(plan_assets(&json).unwrap().legacy);
    }

    #[test]
    fn rejects_bad_asset_hash() {
        let json = r#"{"objects": {"a.txt": {"hash": "../../etc", "size": 1}}}"#;
        assert!(matches!(plan_assets(json), Err(PlanError::BadHash(_))));
    }
}
