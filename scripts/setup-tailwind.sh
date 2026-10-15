#!/usr/bin/env sh
set -eu
version=4.3.3
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
name=tailwindcss-linux-x64
[ "$(uname -s)" = Linux ] && [ "$(uname -m)" = x86_64 ] || { echo 'Bản build này yêu cầu Linux x64.' >&2; exit 1; }
expected=$(awk -v name="$name" '$2 == name { print $1 }' "$root/scripts/tailwind.sha256")
binary="$root/.tools/tailwind/$version/$name"
mkdir -p "$(dirname "$binary")"
if [ ! -f "$binary" ] || [ "$(sha256sum "$binary" | cut -d ' ' -f 1)" != "$expected" ]; then
  curl --fail --location --retry 3 "https://github.com/tailwindlabs/tailwindcss/releases/download/v$version/$name" --output "$binary.download"
  echo "$expected  $binary.download" | sha256sum --check --status || { echo 'SHA-256 Tailwind không đúng.' >&2; exit 1; }
  mv "$binary.download" "$binary"
fi
chmod +x "$binary"
echo "Tailwind $version đã xác minh SHA-256."
