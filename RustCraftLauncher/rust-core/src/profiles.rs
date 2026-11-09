//! Профили игры: проверка, добавление, удаление и подбор версии Java.
//!
//! Файл профилей читает и пишет Kotlin-часть. Сюда приходит текст JSON,
//! а возвращается новый список, уже проверенный.

use crate::GameProfile;
use serde::Serialize;

/// Какая Java обычно нужна версии Minecraft. Это запасная оценка для нового профиля:
/// настоящее значение при скачивании берётся из JSON версии (javaVersion).
pub fn suggest_java(version: &str) -> u8 {
    let mut parts = version.split('.');
    let first: u32 = match parts.next().and_then(|p| p.parse().ok()) {
        Some(n) => n,
        None => return 21, // снапшоты вроде 25w31a
    };
    if first >= 26 {
        return 25;
    }
    if first != 1 {
        return 21;
    }
    let minor: u32 = parts.next().and_then(|p| p.parse().ok()).unwrap_or(0);
    let patch: u32 = parts.next().and_then(|p| p.parse().ok()).unwrap_or(0);
    match minor {
        21.. => 21,
        20 => {
            if patch >= 5 {
                21
            } else {
                17
            }
        }
        18 | 19 => 17,
        17 => 16,
        _ => 8,
    }
}

#[derive(Serialize)]
struct ProfilesResponse {
    ok: bool,
    profiles: Vec<GameProfile>,
    error: Option<String>,
}

fn respond(result: Result<Vec<GameProfile>, String>) -> String {
    let response = match result {
        Ok(profiles) => ProfilesResponse {
            ok: true,
            profiles,
            error: None,
        },
        Err(error) => ProfilesResponse {
            ok: false,
            profiles: Vec::new(),
            error: Some(error),
        },
    };
    serde_json::to_string(&response)
        .unwrap_or_else(|_| r#"{"ok":false,"profiles":[],"error":"serialize failed"}"#.to_string())
}

fn parse_list(json: &str) -> Result<Vec<GameProfile>, String> {
    if json.trim().is_empty() {
        return Ok(Vec::new());
    }
    serde_json::from_str(json).map_err(|e| format!("invalid profiles file: {e}"))
}

/// Читает файл профилей: невалидные записи и повторы id отбрасываются.
pub fn normalize_response(list_json: &str) -> String {
    respond(parse_list(list_json).map(|profiles| {
        let mut result: Vec<GameProfile> = Vec::new();
        for profile in profiles {
            let duplicate = result
                .iter()
                .any(|p| p.id.eq_ignore_ascii_case(&profile.id));
            if profile.validate().is_ok() && !duplicate {
                result.push(profile);
            }
        }
        result
    }))
}

/// Добавляет профиль или заменяет профиль с тем же id (без учёта регистра).
pub fn upsert_response(list_json: &str, profile_json: &str) -> String {
    respond(upsert(list_json, profile_json))
}

fn upsert(list_json: &str, profile_json: &str) -> Result<Vec<GameProfile>, String> {
    let mut profiles = parse_list(list_json)?;
    let mut profile: GameProfile =
        serde_json::from_str(profile_json).map_err(|e| format!("invalid profile: {e}"))?;
    profile.id = profile.id.trim().to_string();
    profile.validate().map_err(|e| e.to_string())?;

    let position = profiles
        .iter()
        .position(|p| p.id.eq_ignore_ascii_case(&profile.id));
    match position {
        Some(index) => profiles[index] = profile,
        None => profiles.push(profile),
    }
    Ok(profiles)
}

pub fn delete_response(list_json: &str, id: &str) -> String {
    respond(parse_list(list_json).map(|mut profiles| {
        profiles.retain(|p| p.id != id);
        profiles
    }))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn profile_json(id: &str, memory: u32) -> String {
        format!(
            r#"{{"id":"{id}","minecraft_version":"1.21.8","loader":"Vanilla","loader_version":null,"java_major":21,"memory_mb":{memory},"render_distance":12}}"#
        )
    }

    fn parse(response: &str) -> serde_json::Value {
        serde_json::from_str(response).unwrap()
    }

    #[test]
    fn suggests_java_for_known_versions() {
        assert_eq!(suggest_java("1.21.8"), 21);
        assert_eq!(suggest_java("1.21"), 21);
        assert_eq!(suggest_java("1.20.6"), 21);
        assert_eq!(suggest_java("1.20.5"), 21);
        assert_eq!(suggest_java("1.20.4"), 17);
        assert_eq!(suggest_java("1.20"), 17);
        assert_eq!(suggest_java("1.19.2"), 17);
        assert_eq!(suggest_java("1.18.2"), 17);
        assert_eq!(suggest_java("1.17.1"), 16);
        assert_eq!(suggest_java("1.16.5"), 8);
        assert_eq!(suggest_java("1.8.9"), 8);
        assert_eq!(suggest_java("1.7.10"), 8);
    }

    #[test]
    fn suggests_java_for_new_and_unknown_versions() {
        assert_eq!(suggest_java("26.1"), 25);
        assert_eq!(suggest_java("25w31a"), 21);
        assert_eq!(suggest_java(""), 21);
    }

    #[test]
    fn upsert_adds_profile() {
        let out = parse(&upsert_response("[]", &profile_json("main", 4096)));
        assert_eq!(out["ok"], true);
        assert_eq!(out["profiles"].as_array().unwrap().len(), 1);
        assert_eq!(out["profiles"][0]["id"], "main");
    }

    #[test]
    fn upsert_accepts_empty_list_text() {
        let out = parse(&upsert_response("", &profile_json("main", 4096)));
        assert_eq!(out["ok"], true);
    }

    #[test]
    fn upsert_replaces_same_id_ignoring_case() {
        let first = upsert_response("[]", &profile_json("Main", 2048));
        let list = parse(&first)["profiles"].to_string();
        let out = parse(&upsert_response(&list, &profile_json("main", 6144)));
        let profiles = out["profiles"].as_array().unwrap();
        assert_eq!(profiles.len(), 1);
        assert_eq!(profiles[0]["memory_mb"], 6144);
    }

    #[test]
    fn upsert_trims_id() {
        let out = parse(&upsert_response("[]", &profile_json("  pack  ", 4096)));
        assert_eq!(out["ok"], true);
        assert_eq!(out["profiles"][0]["id"], "pack");
    }

    #[test]
    fn upsert_rejects_invalid_profiles() {
        for bad in [
            profile_json("", 4096),
            profile_json("../evil", 4096),
            profile_json(".hidden", 4096),
            profile_json("a/b", 4096),
            profile_json("main", 100),
            profile_json("main", 99999),
            profile_json(&"x".repeat(33), 4096),
        ] {
            let out = parse(&upsert_response("[]", &bad));
            assert_eq!(out["ok"], false, "should reject: {bad}");
            assert!(out["error"].is_string());
        }
    }

    #[test]
    fn upsert_rejects_broken_json() {
        let out = parse(&upsert_response("[]", "{ nope"));
        assert_eq!(out["ok"], false);
        assert!(out["error"].as_str().unwrap().contains("invalid profile"));
    }

    #[test]
    fn delete_removes_only_matching_profile() {
        let list = parse(&upsert_response("[]", &profile_json("one", 4096)))["profiles"].to_string();
        let list = parse(&upsert_response(&list, &profile_json("two", 4096)))["profiles"].to_string();
        let out = parse(&delete_response(&list, "one"));
        let profiles = out["profiles"].as_array().unwrap();
        assert_eq!(profiles.len(), 1);
        assert_eq!(profiles[0]["id"], "two");
    }

    #[test]
    fn normalize_drops_invalid_and_duplicate_entries() {
        let list = format!(
            "[{},{},{},{}]",
            profile_json("ok", 4096),
            profile_json("OK", 2048),
            profile_json("../bad", 4096),
            profile_json("other", 100)
        );
        let out = parse(&normalize_response(&list));
        assert_eq!(out["ok"], true);
        let profiles = out["profiles"].as_array().unwrap();
        assert_eq!(profiles.len(), 1);
        assert_eq!(profiles[0]["id"], "ok");
    }

    #[test]
    fn normalize_reports_corrupt_file() {
        let out = parse(&normalize_response("{ not a list"));
        assert_eq!(out["ok"], false);
    }
}
