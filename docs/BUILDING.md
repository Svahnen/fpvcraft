# Building

## Library and tests (any platform with a C++17 compiler)

Requirements: g++ or clang++ (C++17), CMake >= 3.16. Optional: SDL2 and OpenGL development packages for the `fpvinput` module and the `sim_viewer`
tool (e.g. `sudo dnf install SDL2-devel mesa-libGL-devel` or `sudo apt install libsdl2-dev libgl-dev`).

```
cmake -S . -B build
cmake --build build -j8
ctest --test-dir build --output-on-failure
```

Options (all `ON` by default except `FPV_BUILD_SHARED`): `-DFPV_BUILD_TESTS`, `-DFPV_BUILD_INPUT` (needs SDL2), `-DFPV_BUILD_VIEWER` (needs SDL2 and
OpenGL), `-DFPV_BUILD_SHARED=ON` (builds `libfpvcore.so` / `fpvcore.dll` for JNI or FFI). The preset JSON files under `data/presets` are embedded
into the library at configure time; re-run CMake after changing them.

Run the viewer: `./build/sim_viewer --preset 5race` (keys are listed in the README).

## Regenerating data

* Curves: `python3 tools/bake/gen_curves.py`
* Drag tables: `python3 tools/bake/bake_drag_generic.py` (needs numpy; about one minute per preset)
* Sounds: `python3 tools/audio/synth_drone.py` (needs numpy and soundfile); the motor curve table with `python3 tools/audio/motor_ladder.py`

## Minecraft mod

Requirements: JDK 21, podman (Linux library), optionally mingw-w64 (Windows library).

```
cd adapters/minecraft-fabric
./scripts/build-native.sh            # Linux x86_64 library, built in a manylinux_2_28 container, staged in build-native/natives/
./scripts/build-native.sh windows    # additionally cross-builds fpvcore.dll with mingw-w64 (x86_64-w64-mingw32-g++)
./gradlew build                      # build/libs/fpvcraft-<version>.jar with the native libraries bundled
./gradlew runClient                  # development client
```

The Linux library is built inside `quay.io/pypa/manylinux_2_28_x86_64` (glibc 2.28 baseline, static libstdc++), so that it loads on older
distributions and in sandboxed launchers; the script verifies the highest glibc symbol version. For the Windows step, install mingw-w64 (for example
`dnf install mingw64-gcc-c++`). If it lives in a container, set `FPV_BUILD_BOX=<distrobox name>` and the script runs the cross-build there.

Install the jar in the `mods` folder of a Fabric 1.21.1 profile together with Fabric API (see `docs/MINECRAFT.md`).
