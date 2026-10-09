#!/usr/bin/env bash
# Build libfpvcore.so (Linux x86_64, manylinux container via podman) and optionally fpvcore.dll (Windows x86_64, mingw-w64 cross build)
# and stage them for the mod jar.  usage: scripts/build-native.sh [windows]
# Set FPV_BUILD_BOX=<distrobox name> to run the mingw step inside a distrobox that has mingw-w64 installed.
set -e
HERE="$(cd "$(dirname "$0")/.." && pwd)"
ROOT="$HERE/../.."
run() { if [ -n "$FPV_BUILD_BOX" ] && command -v distrobox >/dev/null && [ -z "$CONTAINER_ID" ]; then distrobox enter "$FPV_BUILD_BOX" -- bash -c "$1"; else bash -c "$1"; fi; }
# Linux lib is built in manylinux_2_28 (glibc 2.28 baseline) so it loads in Flatpak launchers / older distros.
# Building on the host distro links against its newest glibc (e.g. 2.43) and crashes elsewhere.
podman run --rm -v "$ROOT":/src:Z -w /src quay.io/pypa/manylinux_2_28_x86_64 bash -c "cmake -S . -B build-shared-compat -DCMAKE_BUILD_TYPE=Release -DFPV_BUILD_SHARED=ON -DFPV_BUILD_INPUT=OFF -DFPV_BUILD_VIEWER=OFF -DFPV_BUILD_TESTS=OFF -DCMAKE_SHARED_LINKER_FLAGS='-static-libstdc++ -static-libgcc' && cmake --build build-shared-compat -j8"
MAXG=$(objdump -T "$ROOT/build-shared-compat/libfpvcore.so" | grep -oE 'GLIBC_[0-9.]+' | sort -Vu | tail -1)
echo "libfpvcore.so max glibc symbol: $MAXG"
case "$MAXG" in GLIBC_2.2[0-8]|GLIBC_2.1*|GLIBC_2.[0-9]) ;; *) echo "ERROR: glibc requirement too new ($MAXG)"; exit 1;; esac
mkdir -p "$HERE/build-native/natives/linux-x86_64"
cp "$ROOT/build-shared-compat/libfpvcore.so" "$HERE/build-native/natives/linux-x86_64/"
echo "staged Linux lib"
# Windows (cross, mingw-w64; e.g. `dnf install mingw64-gcc-c++`): see docs/MINECRAFT.md
if [ "$1" = "windows" ]; then
  run "cd '$ROOT' && cmake -S . -B build-win -DCMAKE_SYSTEM_NAME=Windows -DCMAKE_C_COMPILER=x86_64-w64-mingw32-gcc -DCMAKE_CXX_COMPILER=x86_64-w64-mingw32-g++ -DCMAKE_BUILD_TYPE=Release -DFPV_BUILD_SHARED=ON -DFPV_BUILD_INPUT=OFF -DFPV_BUILD_VIEWER=OFF -DFPV_BUILD_TESTS=OFF -DCMAKE_SHARED_LINKER_FLAGS='-static -static-libgcc -static-libstdc++' && cmake --build build-win -j8"
  mkdir -p "$HERE/build-native/natives/windows-x86_64"
  cp "$ROOT/build-win/libfpvcore.dll" "$HERE/build-native/natives/windows-x86_64/fpvcore.dll" 2>/dev/null || cp "$ROOT/build-win/fpvcore.dll" "$HERE/build-native/natives/windows-x86_64/fpvcore.dll"
  echo "staged Windows lib"
fi
