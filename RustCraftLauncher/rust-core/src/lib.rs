use jni::objects::{JClass, JString};
use jni::sys::jstring;
use jni::JNIEnv;
use serde::{Deserialize, Serialize};

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

#[no_mangle]
pub extern "system" fn Java_dev_rustcraft_launcher_RustBridge_coreVersion(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    let value = env.new_string("RustCraft Core 0.1.0").expect("JNI string");
    value.into_raw()
}

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
}
