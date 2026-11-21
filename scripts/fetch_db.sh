#!/usr/bin/env bash
# Bundles the game database + all NES cheat files inside the APK, so games are
# identified and cheats work with NO internet. (CI runs this automatically.)
set -euo pipefail
cd "$(dirname "$0")/.."

SYSTEMS=(
  "NES|Nintendo - Nintendo Entertainment System"
)

tmp="$(mktemp -d)"
git clone -q --depth 1 --filter=blob:none --sparse https://github.com/libretro/libretro-database "$tmp/db"
paths=()
for s in "${SYSTEMS[@]}"; do
  name="${s#*|}"
  paths+=("cht/$name" "metadat/no-intro/$name.dat")
done
git -C "$tmp/db" sparse-checkout set --no-cone "${paths[@]/#//}"

assets="app/src/main/assets"
rm -rf "$assets/db" "$assets/cheats.zip"
mkdir -p "$assets/db" "$tmp/cht"
for s in "${SYSTEMS[@]}"; do
  key="${s%%|*}"; name="${s#*|}"
  cp "$tmp/db/metadat/no-intro/$name.dat" "$assets/db/$key.dat"
  if [ -d "$tmp/db/cht/$name" ]; then
    mkdir -p "$tmp/cht/$key"
    cp "$tmp/db/cht/$name/"*.cht "$tmp/cht/$key/"
  fi
done
( cd "$tmp/cht" && zip -q -r -9 "$OLDPWD/$assets/cheats.zip" . )

# Box-art names from libretro-thumbnails (file names only, no images) for cover matching.
for sysdir in "NES|Nintendo_-_Nintendo_Entertainment_System"; do
  key="${sysdir%%|*}"; repo="${sysdir#*|}"
  git clone -q --depth 1 --filter=blob:none --no-checkout "https://github.com/libretro-thumbnails/$repo" "$tmp/thumbs_$key"
  git -C "$tmp/thumbs_$key" ls-tree -r --name-only HEAD \
    | sed -n 's#^Named_Boxarts/\(.*\)\.png$#\1#p' \
    | grep -v '\[' | grep -v -E '\((19|20)[0-9x]{2}' \
    > "$assets/db/$key.boxarts.txt"
  echo "box arts $key: $(wc -l < "$assets/db/$key.boxarts.txt")"
done
date -u +%Y-%m-%d > "$assets/db/version.txt"
rm -rf "$tmp"
echo "Offline database ready:"; ls -la "$assets/db" "$assets/cheats.zip"
