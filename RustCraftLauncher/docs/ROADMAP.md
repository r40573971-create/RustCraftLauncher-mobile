# Roadmap

## M0 — project foundation (current)
- [x] Android Compose shell
- [x] Rust `cdylib` and JNI handshake
- [x] Profile model and validation
- [ ] Build and verify on physical ARM64 device

## M1 — version metadata and profiles
- [ ] Parse Mojang version manifest and version JSON
- [ ] Model libraries, asset indexes, native classifiers and launch arguments
- [ ] Resolve Java major version per Minecraft release
- [ ] Persist profiles in app-private storage

## M2 — game and loader installation
- [ ] Authenticated account flow using supported Microsoft authentication
- [ ] Download game metadata, libraries and assets from official endpoints
- [ ] Install Fabric / Forge / NeoForge using their official metadata/installers
- [ ] Verify hashes and support resumable downloads
- [ ] Never bundle proprietary game files or bypass authentication

## M3 — Android runtime
- [ ] Integrate an Android-compatible ARM64 JVM
- [ ] Native library extraction and classpath construction
- [ ] JVM lifecycle, signals, logs and crash reporting
- [ ] Per-version Java runtime selection

## M4 — graphics and input
- [ ] Integrate a proven Android Minecraft Java rendering bridge
- [ ] Surface lifecycle and Vulkan/OpenGL interop
- [ ] Touch controls, keyboard, mouse and controller
- [ ] Frame pacing, thermal-aware settings and resolution scaling

## M5 — compatibility
- [ ] Test Vanilla versions
- [ ] Test Fabric
- [ ] Test Forge
- [ ] Test NeoForge 1.21.1
- [ ] Publish a device compatibility matrix
