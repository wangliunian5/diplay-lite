# Upstream Dependency & Porting Notes

This document describes the relationship between DiPlay Lite and its upstream
projects: which code comes from upstream, which fixes were ported selectively,
and which parts are original work. It complements the attribution requirements
in [LICENSE](LICENSE) and the disclaimer in [README.md](README.md).

## Upstream projects & attribution

- **DiPlay / shihabal3amri**: https://github.com/shihabal3amri/DiPlay — the direct upstream of this fork
- **xcertplay / shilapi**: https://github.com/shilapi/xcertplay — CarPlay receiver protocol stack basis
- **DiAuto / shihabal3amri**: UI style reference (AGPL-3.0)

Original author and contributor attribution is preserved. This fork is an
independent community modification, not an official upstream release.

## 1. Baseline — the product foundation comes from upstream source

The `shared` module (~99 Kotlin files) is taken from the upstream CarPlay
receiver stack, covering:

- iAP2 handshake and MFi pairing/authentication (PairSetup / PairVerify / SRP6a)
- AirPlay session and audio/video engine (Opus codec in `native/`, BSD-licensed)
- NCM / usbmuxd wired transport, Wi-Fi wireless link layer
- Bonjour / mDNS service discovery
- Vehicle integration bridges (e.g. BYD HUD)

Porting was done as **API 16 trimming/adaptation**, not a verbatim copy:
~16 compatibility rework classes (Closeable replacements, Bluetooth/hotspot
reflection fallbacks, forced JmDNS 4.x, MediaCodec/AudioTrack/AudioRecord
compatibility, lint NewApi cleanup). The overall app shape (jailbreak-free
head-unit receiver, experimental MFi identity, local authentication) follows
the upstream model.

## 2. Selectively ported fixes/features

| Upstream source | Content | Where applied here |
| --- | --- | --- |
| #5 / #6 | wireless handoff merge criteria + bandwidth monitoring | early low-API branch |
| 0.2.8 | 512KB audio UDP receive buffer (holes/underrun mitigation) | early low-API branch |
| #75 / #76 | decoder & pairing stability for low-spec units | early low-API branch |
| 0.2.10 #116 | call quality: telephony AEC/NS + MODE_IN_COMMUNICATION | early low-API branch |
| #229 | recovery speedup + AirPlay stall watchdog (AC8227 flicker fix) | v1.0.29 — the only explicitly labeled port on the Lite mainline |
| PR #143 | AirPlay fallback when port 7000 is occupied | re-implemented as a 3-tier fallback (retry / blacklist / ephemeral port) in vc67 |
| #37 | steering-wheel key handling | 0.2.7-ch branch |
| disableAudioOutput mechanism | reused to implement the "audio over Bluetooth" switch | mechanism reused, productized fallback |

## 3. Original work (not ported)

- Wireless speedup: direct selection of the single bonded iPhone, fully
  backgrounded Bonjour, hotspot-kept-for-reuse
- TS7 wired CarPlay four-layer fix: NCM group selection reversal (MUSB EP 0x07
  TX drop avoidance), setup watchdog, NTB16 desync resync with windowed storm
  counter, WiredBonjourAdvertiser, WiredNcmGuard
- EU5 microphone fallback matrix (source × sample-rate × channel with linear resampling)
- AirPlay listen-port 3-tier fallback, EU5 watchdog false-kill fix, 8815
  render-pipeline fixes, system diagnostics/forensics, in-app license notice

## 4. Upstream mirror branches

`sync/author-upstream` and `sync/0.2.7-backup` are read-only mirrors of
upstream 0.2.7/0.2.8 for diffing only; they are never merged into product branches.
