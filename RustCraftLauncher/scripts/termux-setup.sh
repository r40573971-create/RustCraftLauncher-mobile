#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

pkg update -y
pkg upgrade -y
pkg install -y git rust clang make pkg-config nano

echo
echo "Termux tools installed."
rustc --version
cargo --version
git --version
echo
echo "Next: cd into the RustCraftLauncher folder and run:"
echo "  cargo test --manifest-path rust-core/Cargo.toml"
