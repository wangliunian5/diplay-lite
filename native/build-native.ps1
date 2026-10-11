<#
  Builds libxcertplay_opus.so (armeabi-v7a + arm64-v8a) and copies it into
  shared/src/main/jniLibs/<abi>/, then lists undefined symbols for API16 review.

  Why a standalone script instead of Gradle externalNativeBuild:
  this repo lives under a path containing spaces, and ndk-build fails there with
  "APP_BUILD_SCRIPT points to an unknown file". So we stage the jni dir into a
  space-free directory (default C:\dpbuild\lite-ndk) and build there. This keeps the
  normal Lite Gradle build (which does run in the spaced path) working.

  NOTE: keep this file ASCII-only. Windows PowerShell 5.1 reads .ps1 as ANSI unless it
  has a UTF-8 BOM, so non-ASCII comments here break parsing.

  Usage:
    powershell -ExecutionPolicy Bypass -File build-native.ps1
    powershell -ExecutionPolicy Bypass -File build-native.ps1 -Ndk C:\Android\android-ndk-r21e
#>
param(
    [string]$Ndk = "C:\Android\android-ndk-r23b",
    [string]$StageRoot = "C:\dpbuild\lite-ndk"
)

$ErrorActionPreference = "Stop"

$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$jniSrc = Join-Path $here "jni"
$jniLibs = Join-Path (Split-Path -Parent $here) "shared\src\main\jniLibs"

$ndkBuild = Join-Path $Ndk "ndk-build.cmd"
if (-not (Test-Path $ndkBuild)) {
    throw "ndk-build.cmd not found at $ndkBuild ; pass -Ndk <ndk root> (needs one that still supports APP_PLATFORM=android-16: r23b/r21e)"
}

if (Test-Path $StageRoot) { Remove-Item $StageRoot -Recurse -Force }
New-Item -ItemType Directory -Path $StageRoot -Force | Out-Null
Copy-Item $jniSrc -Destination (Join-Path $StageRoot "jni") -Recurse -Force

Write-Host "[build] staging=$StageRoot ndk=$Ndk"
& $ndkBuild -C $StageRoot -j 8
if ($LASTEXITCODE -ne 0) { throw "ndk-build failed (exit=$LASTEXITCODE)" }

$abis = @("armeabi-v7a", "arm64-v8a")
foreach ($abi in $abis) {
    $built = Join-Path $StageRoot "libs\$abi\libxcertplay_opus.so"
    if (-not (Test-Path $built)) { throw "missing output $built" }
    $destDir = Join-Path $jniLibs $abi
    New-Item -ItemType Directory -Path $destDir -Force | Out-Null
    Copy-Item $built -Destination (Join-Path $destDir "libxcertplay_opus.so") -Force
    Write-Host ("[ok] {0}  {1} bytes" -f $abi, (Get-Item $built).Length)
}

# Compatibility review: every undefined symbol must be an old libc/libm/libdl/liblog one
# that already exists on API16.
$readelf = Join-Path $Ndk "toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-readelf.exe"
if (Test-Path $readelf) {
    foreach ($abi in $abis) {
        $so = Join-Path (Join-Path $jniLibs $abi) "libxcertplay_opus.so"
        Write-Host "--- undefined symbols ($abi) ---"
        & $readelf -sW --dyn-syms $so |
            Select-String " UND " |
            ForEach-Object { ($_ -split '\s+')[-1] } |
            Sort-Object -Unique
    }
    Write-Host "--- NEEDED (armeabi-v7a) ---"
    & $readelf -d (Join-Path (Join-Path $jniLibs "armeabi-v7a") "libxcertplay_opus.so") |
        Select-String "NEEDED"
} else {
    Write-Host "[warn] llvm-readelf not found, skipping symbol review: $readelf"
}