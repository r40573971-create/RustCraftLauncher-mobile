# Android runtime and graphics integration

This is the highest-risk subsystem.

A normal desktop Minecraft Java launch expects a desktop JVM, LWJGL and desktop window/input
semantics. Android requires an ARM64-compatible JVM build and an Android-specific graphics,
window and input bridge. Merely embedding a JVM does not make desktop LWJGL work.

Recommended engineering approach:
1. Reuse a mature Android Java-game runtime/graphics bridge where licensing permits.
2. Keep the launcher and orchestration code in Rust.
3. Keep the official mod-loader runtime intact on the JVM.
4. Do not attempt a custom Rust reimplementation of NeoForge APIs.
5. Benchmark on the target phone before claiming FPS improvements.

The app scaffold currently has no game runtime and cannot launch Minecraft yet.
