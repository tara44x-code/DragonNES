#!/usr/bin/env bash
# Fetches LibretroDroid 0.14.0 (GPLv3) with its submodules (oboe, libretro-common),
# applies DragonNES patches (DSLR lens shader + aspect-ratio override for true full screen)
# and places the sources in libretrodroid/src, where the :libretrodroid module builds them.
set -euo pipefail
cd "$(dirname "$0")/.."

TAG="0.14.0"
COMMIT="8835c3098514390a271e36983957f7bb5f40abf1"
dest="libretrodroid/src"

if [ -f "$dest/.dragonnes-patched" ] && [ "$(cat "$dest/.dragonnes-patched")" = "$COMMIT+$(cat libretrodroid/patches/*.patch | sha1sum | cut -c1-12)" ]; then
  echo "LibretroDroid already prepared."; exit 0
fi

tmp="$(mktemp -d)"
git clone -q --depth 1 --branch "$TAG" https://github.com/Swordfish90/LibretroDroid "$tmp/lrd"
[ "$(git -C "$tmp/lrd" rev-parse HEAD)" = "$COMMIT" ] || { echo "Unexpected LibretroDroid commit"; exit 1; }
git -C "$tmp/lrd" submodule update -q --init --depth 1
for p in libretrodroid/patches/*.patch; do
  echo ">> applying $p"
  git -C "$tmp/lrd" apply --whitespace=nowarn "$PWD/$p"
done

rm -rf "$dest"
cp -r "$tmp/lrd/libretrodroid/src" "$dest"
# Samples/tests of the submodules are not needed for the build.
rm -rf "$dest/main/cpp/oboe/samples" "$dest/main/cpp/oboe/apps" "$dest/main/cpp/oboe/tests" "$dest/main/cpp/oboe/docs"
echo "$COMMIT+$(cat libretrodroid/patches/*.patch | sha1sum | cut -c1-12)" > "$dest/.dragonnes-patched"
rm -rf "$tmp"
echo "LibretroDroid ready (patched)."
