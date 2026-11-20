# WG / AWG import (Android)

`Добавить серверы → Из буфера → Импорт` accepts wg-quick / awg-quick `[Interface]` / `[Peer]` text.
`Добавить серверы → Из файла` opens Android's document picker (including Downloads and providers that label `.conf`
`application/octet-stream`). Reading is bounded to 1 MiB, strict UTF-8 with optional BOM, on the IO dispatcher.
The existing confirmation dialog is shown before saving. No scripts are executed, keys/configuration text are not logged.

A single client peer is supported. PrivateKey, PublicKey, optional PresharedKey, IPv4/IPv6 Endpoint, Address,
MTU, AllowedIPs and integer PersistentKeepalive are preserved and sent to the outbound.
Repeated Address/AllowedIPs/DNS lines are combined. Malformed/unknown directives and multiple peers are rejected
as a whole, not reduced silently to the last peer. AWG permits at most one interface address per address family
(the mihomo adapter exposes only one IPv4 and one IPv6). Filenames supply a display name, not a protocol version.

This is an outbound proxy client, not wg-quick executing a routing script:
- DNS from the file is retained in `wgDns` for inspection/export; **the app's DNS settings remain authoritative**.
- AllowedIPs restrict the peer; the app's own traffic routing rules still choose which traffic enters this outbound.
- ListenPort, Table and SaveConfig do not alter the app's interface/routing/settings. The import dialog states this.
- Hooks (PreUp/PostUp/PreDown/PostDown) and unknown options are rejected, never executed.
- PersistentKeepalive is limited to an integer 0–65535. AWG3.x supports ranged keepalive upstream, but the pinned
  mihomo adapter exposes only an integer, so a range is **rejected**, not silently truncated.
- No claim is made about AWG “2.5”: it is not a separately confirmed upstream format here.

## Protocol versions and actual runtime

No core update or native binary replacement is needed. Both shipped `libmhx.so` ABIs report with `go version -m`:
- `github.com/metacubex/mihomo v1.19.34`
- `github.com/metacubex/amneziawg-go v0.0.0-20260908071407-0c1c6f40ecd7`

WG `.conf` uses the existing **Xray WireGuard** outbound. AWG uses the existing **mihomo** WireGuard outbound.
AWG1.x/1.5/2.0 use the legacy runtime; AWG3.0/3.1 use `amnezia-wg-option.version: 3` (the core selects `device`,
not `device_v1`). Previously the app omitted both that selector and the advanced parameters.

Parameters now mapped from `.conf`, `awg://`/`amneziawg://`, and AWG configuration inside `vpn://`:
- Legacy/common: Jc/Jmin/Jmax, S1–S4, H1–H4 (including ranges), I1–I5.
- AWG1.5-specific: J1–J3 and Itime (not combined with v3-only settings).
- AWG3.0: HeaderProtectionKey, ContentPaddingAddition, RekeyAfterTime, RekeyTimeout, RejectAfterTime,
  KeepaliveTimeout, MaxHandshakeAttempts. Presence of a v3-only parameter selects runtime version 3.
- AWG3.1: RandomTrailers and DisableCookies, including explicit false values.
- HeaderProtectionKey must be 32 bytes Base64; with it all S1–S4 must be >=12. Unknown version selectors,
  malformed numeric ranges and mixed legacy-only/v3-only fields fail validation.

Existing manual AWG form is still the basic/common parameter form. For extended 1.5/3.x use `.conf` or a share link.

## Evidence / tests

Primary upstream documentation:
- https://github.com/amnezia-vpn/amneziawg-go/blob/master/README.md
- https://docs.amnezia.org/documentation/instructions/new-amneziawg-selfhosted/
- Pinned adapter's options and IPC mapping:
  https://github.com/MetaCubeX/mihomo/blob/v1.19.34/adapter/outbound/wireguard.go
- Pinned runtime:
  https://github.com/MetaCubeX/amneziawg-go/tree/0c1c6f40ecd7

`WireGuardImportTest` covers clipboard/file parser equivalence, BOM/CRLF, IPv6, repeated fields, key validation,
AllowedIPs/keepalive mapping, AWG1.5 legacy options, AWG2.0 ranges, AWG3.0/3.1 mapping and runtime selection,
share links, rejection without key leakage and bounded/UTF-8 file reads.

`android/native/mihomo-lite/awg_e2e_test.go` (`TestWGAndAWGVersions`) configures a real userspace WireGuard/AWG
server over loopback UDP and sends TCP echo through the pinned mihomo client for WG, AWG1.5, 2.0, 3.0, 3.1,
and 3.1 without content padding (random trailers alone). The cases exercise all I1–I5, J1–J3/Itime, v3 timing
fields, header protection, content padding, RandomTrailers and DisableCookies. Server keys are generated in memory.
The server is the pinned Go runtime, not an independent implementation. The plain WG native case checks mihomo;
the imported WG/Xray path is covered by Kotlin configuration tests, not a new Xray network integration test.

These are **local software tests**, not an Android device test, a test against the user's server, or a guarantee
that every server-side deployment/configuration option is interoperable. No AWG2.5 assertion or release is made.
