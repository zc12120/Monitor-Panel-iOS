# Porting baseline

Reference: iOS commit `28cb72087568599ff98eeaaf597d61149aa43e7b`, version 1.0.1 build 15.
The user's requirement is a direct Android port, not a redesign.

## Interface contract

Intent: a server owner checks availability, load and traffic on a phone, opens node history, and deliberately performs Komari management actions.

Domain: nodes, online/offline state, CPU/load, traffic quota, Ping targets, panel connections, execution results and audit records.

Palette: use the original grouped gray canvas (`#F2F2F7`), white cards, gray metadata, blue actions/traffic, green online/CPU, teal memory, orange offline/disk, and red loss. Dark mode uses the original black/grouped charcoal hierarchy.

Signature: the original four-column node card (CPU / memory / disk / traffic) above throughput, transferred bytes and two eight-bar latency/loss indicators. Keep this structure in the overview and expand it into the original two-column detail card.

Depth: grouped surface colors, no decorative shadows. Typography: Android's system sans with bold node names and compact data labels. Spacing: original 4-point rhythm; 16 dp page inset, 14 dp node-card inset, 12 dp card gaps. Preserve rounded groups and the floating three-tab capsule.

Avoid generic dashboard substitutions: retain compact node cards instead of oversized KPI tiles, the original bottom navigation instead of a desktop sidebar, and named status colors instead of an unrelated palette. System-specific controls, font glyphs, Back handling and Apple-only glass effects have platform implementations.

## Implementation mapping

| iOS | Android |
| --- | --- |
| `Core.swift` | `Models.kt`, `Api.kt`, `Credentials` |
| `Backends.swift` | `Api.kt` normalization and history |
| `PanelStore.swift` | `PanelStore.kt`, `ConnectionGate` |
| `App.swift` | `MainActivity.kt`, `Dashboard.kt`, `Panels.kt`, `NodeDetail.kt` |
| `NodeCard.swift`, `PingSummary.swift` | `Dashboard.kt`, `Models.kt` |
| `Management.swift` | `Management.kt` |
| `NodeIdentity.swift` | `I18n.kt` identity helpers and original PNGs |
| `Appearance.swift`, `Compatibility.swift` | `Theme.kt`, appearance and about screens |

Resource regeneration: `python3 android/scripts/sync-ios-resources.py` from the repository root. Original translations are copied; Android-specific wording is maintained separately in that script.

## Verification scope

JVM contract tests exercise actual HTTP requests against a local mock server, redirect rejection, auth headers, cancellation, JSON-RPC envelopes, all backend normalizers, unknown metrics, traffic/Ping aggregation, ordering/hiding, management conflict detection/readback, and out-of-order connection completion.

Robolectric Android UI tests render original-style overview (light/dark), node details, empty state and management destinations, and exercise search/navigation. Screenshots are generated under `app/build/screenshots/`.

These are automated local tests. A successful build does not establish connectivity with the owner's production panels or replace on-device acceptance testing.
