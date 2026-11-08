# RustCraft Launcher

An Android launcher foundation for Minecraft Java with a Kotlin UI, Rust core, JNI boundary,
and a separately integrated Java runtime / game renderer.

## Status

This is a **starter scaffold**, not a working Minecraft launcher yet. It does not bundle
Minecraft, a JVM, game assets, authentication, Forge/Fabric/NeoForge installers, or a renderer.
Those pieces require separate implementation and compatibility testing.

## Target device

Initial target: Xiaomi 13T Pro (ARM64, Android 15, Dimensity 9200+).

## Architecture

- `app/`: Android application and minimal Compose UI.
- `rust-core/`: Rust library exposed through JNI. Owns version metadata, profile config,
  validation, and process planning.
- `runtime/`: design notes for the Android JVM and graphics integration.
- `docs/`: milestones and compatibility plan.

## Build prerequisites

- Android Studio with Android SDK
- JDK 17 for the Android Gradle build
- Rust stable + `cargo`
- Android NDK and `cargo-ndk`
- Android target `aarch64-linux-android`

The Android app scaffold can be opened in Android Studio. The native library is a separate
Cargo project and must be built for the Android ABI before JNI loading can succeed.

## Important compatibility facts

1. A Rust launcher can manage Minecraft, but NeoForge/Forge/Fabric still execute on a JVM.
2. A desktop JVM cannot simply be copied to Android. An Android-compatible Java runtime and
   a supported graphics bridge are required.
3. Minecraft versions and mod loaders have different Java requirements. The launcher must
   resolve the required Java major version per game profile.
4. Offline-mode server access is a server configuration, not a launcher feature. Only join
   servers you are authorized to access; do not use this project to bypass account checks.
5. Minecraft game files and assets must be obtained through legitimate game installation /
   account flows. This repository contains no proprietary game files.

## First milestone

The first milestone is deliberately modest:
- app shell and navigation
- Rust JNI handshake
- profile model
- version/loader compatibility metadata model
- launch-plan validation (not actual game launch)

Actual JVM bootstrap, game download, mod-loader installation and rendering are later milestones.
