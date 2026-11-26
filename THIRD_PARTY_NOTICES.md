# Third-party notices

Vortex Launcher's own code is licensed under the MIT License (see `LICENSE`).
This file lists third-party components and their licenses.

Vortex Launcher is an unofficial project. It is not affiliated with, endorsed
by, or sponsored by Mojang Studios or Microsoft. "Minecraft" is a trademark of
Mojang Studios. Vortex Launcher does not redistribute Minecraft game files: they
are downloaded on the user's device from Mojang's servers.

## Used in the project now

Check these against `app/build.gradle*` and `Cargo.lock` before each release.

| Component | License |
|---|---|
| Kotlin and Kotlin standard library | Apache-2.0 |
| AndroidX and Jetpack Compose | Apache-2.0 |
| Apache Commons Compress | Apache-2.0 |
| Rust crates used by the Native Core | see `Cargo.lock` and each crate's license |
| Fabric Meta / Fabric Maven (downloaded at runtime) | see https://fabricmc.net |

## Planned, NOT yet included

These are candidates for the launch layer. Do not ship them until the exact
license and version have been verified and the notes below are complete.

| Component | Expected license | Integration requirement |
|---|---|---|
| PojavLauncher-derived launch core | LGPL-3.0 | Keep as a separate module/library; publish its source and the license text |
| MobileGlues (renderer) | LGPL-2.1 | Ship as a separate `.so`; publish source and license text |
| GL4ES / Holy GL4ES (renderer) | MIT (verify per fork) | Keep copyright notice |
| Mesa (Zink) | MIT | Keep copyright notice |
| LWJGL 3 | BSD-3-Clause | Keep copyright notice |
| OpenJDK Android builds (JRE) | GPLv2 (check Classpath exception) | Provide license text and source of the exact build used |
| OpenAL Soft | LGPL (verify exact version) | Separate library; publish source and license text |
| ANGLE | verify before use | Sources disagree on license status |

Do not copy code from GPL-licensed launchers (for example ZalithLauncher 2 and
its plugins) into this project.

## Required notices for each included component

For every component moved from "planned" to "included", add here: name,
exact version, license name, link to source, and the full license text (or a
link to it).
