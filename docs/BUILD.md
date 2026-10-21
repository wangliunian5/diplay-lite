# Building DiPlay Lite

Pinned toolchain below. Read the notes before changing any version — most of these pins exist
because upgrading breaks the API 16 baseline or the R8 pass.

## Requirements

| Component | Version | Note |
|---|---|---|
| JDK | **17** | Kotlin 1.8.22's `JavaVersion.parse` fails on JDK 25's four-segment version string |
| Gradle | 7.6.4 | Provided by the included wrapper |
| AGP | 7.4.2 | Cannot consume platform `android-37` |
| Kotlin | 1.8.22 | |
| Android SDK | compileSdk / targetSdk **34** | minSdk is 16 (Android 4.1) |
| NDK | Only for the local Opus native build | CI does not depend on it |

If your `JAVA_HOME` points elsewhere, pin it explicitly in `diplay-lite/gradle.properties`:

```properties
org.gradle.java.home=C:/Android/jdk-17/jdk-17.0.2
```

## Source-only build

The project has three modules: `:app`, `:shared` and `:blackbar`.

```sh
./gradlew :app:assembleDebug :shared:assembleDebug
```

Without the identity input described below, the resulting APK contains **no accessory identity**.
It compiles and installs, but cannot authenticate with CarPlay — this is intentional for
source-only and CI builds. Tests generate synthetic identities at runtime; no test private-key
files are tracked.

## Identity assets are external — never in the repository

The receiver needs an experimental CarPlay accessory identity at runtime. **`identity.pk8` and
`certificate.p7b` are not part of the source tree**; they are injected at build time.

| Priority | Source | Use case |
|---|---|---|
| 1 | `DIPLAY_IDENTITY_DIR` environment variable | CI, or your own directory |
| 2 | `auth/offline-mfi/` alongside the repository root | Default on a local machine |
| 3 | Neither present | Build continues without identity (see above) |

Only those two filenames are accepted; if only one is present the build fails.

```sh
DIPLAY_IDENTITY_DIR=/absolute/path/to/assets ./gradlew :app:ciRelease
```

`ciRelease` runs `injectRuntimeIdentity` (hooked into `preBuild`), then assembles, signs and
archives to `apks/DiPlay-<version>-<flavor>-vc<code>.apk`. Override the archive location with
`DIPLAY_APK_ARCHIVE_DIR`.

**Always verify the identity actually landed in the APK.** A missing identity produces an APK
that installs fine but silently fails to authenticate, which is easily misdiagnosed as a
protocol or handshake problem:

```sh
python -c "import zipfile;z=zipfile.ZipFile('apks/<apk>');print([n for n in z.namelist() if 'offline-mfi' in n])"
# expect: assets/offline-mfi/identity.pk8, assets/offline-mfi/certificate.p7b
```

**Never commit identity assets to any repository**, public or private. CI runs
`scripts/check_public_tree.py` as its first step and fails the build if key material or
credentials enter the index. Run it locally before committing:

```sh
python3 scripts/check_public_tree.py
```

## Android signing key

Set `ANDROID_KEYSTORE_PATH`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS` and
`ANDROID_KEY_PASSWORD` for your own key. Otherwise `ciRelease` falls back to
`~/.android/debug.keystore`, whose alias is **`androiddebugkey`** (not `android`) with every
password `android`. If that keystore is absent too, the APK stays unsigned. Different signing
keys cannot update an existing installation.

Never commit these values or the keystore itself.

## Third-party dependencies

Limited to `org.jmdns:jmdns:3.5.1` and `org.bouncycastle:bcprov-jdk15on:1.70`.

Do **not** upgrade jmdns: 3.5.4+ calls `Map.getOrDefault`, a Java 8 API, which throws
`NoSuchMethodError` on API 16.

## Notes

- This document previously described an upstream layout (`:common` / `:mobile` modules, JDK 25,
  SDK 37, `DIPLAY_AUTH_ASSETS_DIR`). Those no longer exist here; the current text reflects
  the actual project as of 2026-10-21.
- See `INSTALL.md` for setting up a device, and `TESTING.md` for the verification checklist.
