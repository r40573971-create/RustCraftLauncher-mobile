# Developing RustCraft on an Android phone

## Recommended workflow

Use the phone for editing and Rust tests. Use GitHub Actions to compile the Android APK.
A full Android SDK + NDK + Gradle + Rust cross-compilation toolchain is large and awkward
to maintain on a phone. Cloud build is more repeatable.

## 1. Install tools

1. Install Termux from the official GitHub releases:
   https://github.com/termux/termux-app/releases
2. Install Acode or another text editor.
3. Create a GitHub account if you do not already have one.

Open Termux and run:

```bash
termux-setup-storage
pkg update -y && pkg upgrade -y
pkg install -y git rust clang make pkg-config nano
```

## 2. Put the project in GitHub

1. Create a new empty GitHub repository named `RustCraftLauncher`.
2. Upload the contents of this project ZIP (not the ZIP itself) into the repository.
   You can use GitHub's website in your phone browser.
3. Make sure `.github/workflows/android-debug.yml` is present in the repository.
4. The default branch should be named `main`.

## 3. Run Rust tests in Termux

Download your repository in Termux (replace USER with your GitHub username):

```bash
git clone https://github.com/USER/RustCraftLauncher.git
cd RustCraftLauncher
cargo test --manifest-path rust-core/Cargo.toml
```

The first Rust build downloads crates and can take a while. This tests only the Rust core,
not Minecraft or the Android game runtime.

## 4. Build the Android APK

After pushing the project to the `main` branch:

1. Open the repository on GitHub.
2. Open the Actions tab.
3. Select `Android debug APK`.
4. Press `Run workflow` if it has not started automatically.
5. Wait for the workflow to finish.
6. Open the completed run and download the `rustcraft-debug-apk` artifact.
7. Extract the artifact ZIP and install `app-debug.apk`.

The current APK is only the launcher shell. It does not launch Minecraft yet.

## 5. Important current limitations

- The Rust library is not yet wired into the Android Gradle build. The UI reports `not built yet`.
- The launch button is intentionally disabled.
- No JVM, Minecraft files, authentication, loader installer, or graphics bridge is included.
- The first cloud workflow compiles the Android UI only. Native Rust cross-compilation and packaging
  are a separate next milestone.
- Do not download random APKs or game files from unofficial mirrors.
