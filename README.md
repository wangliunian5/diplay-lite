# DiPlay

> ## DiPlay Lite — Chinese community fork (README top notice)
>
> This repository contains **DiPlay Lite**, an independent community-maintained fork of
> [shihabal3amri/DiPlay](https://github.com/shihabal3amri/DiPlay) (upstream of [shilapi/xcertplay](https://github.com/shilapi/xcertplay) lineage),
> adapted for low-spec Chinese-market Android head units (Android 4.1+ / various OEM platforms).
> It is maintained on a **non-commercial** basis for personal research, owner-to-owner exchange and
> compatibility maintenance. No commercial sales, paid licensing or commercial support is provided.
> This positioning does not alter the rights granted by the original code licenses; assets may carry
> their own restrictions, see below.
>
> ### License & attribution
> - Receiver and derivative code: upstream **GPL-3.0** retained, together with existing notices. GPL itself permits commercial use and redistribution, subject to its obligations.
> - UI adapted from **DiAuto** (AGPL-3.0) — preserve those notices when distributing.
> - Upstream projects and original contributors keep full authorship credit.
>
> ### Trademarks
> CarPlay, iPhone, iPad and Apple are trademarks of Apple Inc.; OEM brand names and logos (BYD, Geely,
> Ora/Haval, Chery, etc.) belong to their respective owners. Neither the code license nor this project
> grants any right to use those marks, and no affiliation with or endorsement by Apple, any OEM or the
> upstream authors is implied.
>
> ### Experimental accessory identity
> This is **not an Apple-certified product**. Builds may bundle an experimental accessory identity
> recovered from publicly available third-party firmware. The **source repository excludes all
> authentication keys and Android signing keys**; runtime identity assets are described in the build
> instructions. Future iOS updates may break it at any time.
>
> ### Warranty & safety
> The software is provided **AS IS**, without warranty of any kind. Compatibility is verified only on
> specific tested platforms/firmware/iOS combinations; nothing is promised for any other device.
> You install and use it **at your own risk**. Head units are driving-related equipment — do not
> operate or configure the app while driving, and obey local laws.
>
> ### Device modification & diagnostics
> Some features (wired CarPlay on certain platforms, adb self-boot, NCM guard) rely on **modifying your
> own head-unit system** (root/adb). Such actions may void warranties or brick devices and are entirely
> the user's responsibility. Diagnostic logs leave the device **only when you explicitly trigger an
> upload**; the log-upload credential is injected at build time and never committed.
>
> ---
>
> The remainder of this README is the upstream project's description, preserved for reference.

**CarPlay for compatible Android head units.** Wired and wireless, with the familiar DiAuto interface. Independent app: `com.shihab.diplay`.

[Download & website](https://shihabal3amri.github.io/DiPlay/) · [Release](https://github.com/shihabal3amri/DiPlay/releases/tag/v0.2.0) · [Report a problem](https://github.com/shihabal3amri/DiPlay/issues/new/choose)

![DiPlay home](site/assets/home.png)

## 0.2.0 — public preview

Install on the **car**, not the iPhone. No jailbreak, dongle, Mac, account or authentication server is required for use. ADB is not needed during everyday use; your head unit must permit APK installation. Wireless supports Wi-Fi Direct or the car’s existing hotspot; Wi-Fi Direct requires Android 10+; the APK supports Android 9+ for wired use.

- Wired USB and wireless CarPlay with local authentication.
- BYD HUD navigation with arrows, distance and street names on verified firmware.
- Car hotspot support, improved audio buffering and saved receive diagnostics.
- Automatic address discovery, fixed-channel Wi-Fi fallbacks and successful-configuration memory.
- Icon/text size, resolution and frame rate; applying a display change reconnects CarPlay.
- Local diagnostic export. Reports are sent only if you choose to share them.
- Separate installation alongside DiAuto. Run one projection app at a time.

This is **not an Apple-certified product**. The APK bundles an experimental accessory identity recovered from public Carlinkit firmware, not a newly provisioned MFi identity for DiPlay. A bundled private key is extractable. Acceptance after future iOS updates, reliability across head units and suitability of that identity for general distribution are unresolved. This release invites community testing; it is not a guarantee of universal compatibility.

The release changes were tested on the development DiLink5.1 car: live windshield guidance and street names work, Car hotspot now starts CarPlay, and Wi-Fi Direct performance is substantially improved. Occasional audio cutouts remain and are deferred to a later update. Broader head-unit and iOS compatibility is not guaranteed. The HUD firmware scope and cleanup limits are documented in [BYD navigation](docs/BYD_NAVIGATION.md).

## Documentation

- [Install and connect](docs/INSTALL.md)
- [Compatibility and troubleshooting](docs/COMPATIBILITY.md)
- [Privacy and diagnostic reports](docs/PRIVACY.md)
- [Build from source](docs/BUILD.md)
- [Validation](docs/VALIDATION.md)
- [Release notes](CHANGELOG.md)
- [Credits and licenses](docs/THIRD_PARTY_NOTICES.md)

The website is available in English, Arabic, Russian, Spanish and Simplified Chinese. The current app interface is English.

## Source and credits

Based on [xcertplay](https://github.com/shilapi/xcertplay), GPL-3.0. The home/settings UI and website adapt [DiAuto](https://github.com/shihabal3amri/DiAuto), AGPL-3.0; that license is included in `docs/licenses`. Preserve those notices when distributing modifications. CarPlay and its icon belong to Apple Inc.; no Apple or BYD affiliation or endorsement is implied.

This repository starts with a clean public source snapshot. Local research, tester reports and release-signing secrets are excluded. The complete source corresponding to the APK is provided with every release; experimental runtime identity assets are described separately in the build instructions and notices.

## Local release packaging

The release APK intentionally contains the experimental accessory identity. The Git repository and source archive exclude all accessory and Android signing keys; tests generate synthetic identities at runtime. Source/CI builds omit runtime identity assets by default. Local release builds explicitly select an external asset directory. Publishing the APK makes its bundled identity extractable; building locally does not preserve that identity's confidentiality.
