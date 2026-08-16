# PingLab

A real, offline-capable network measurement app for Android, written in Kotlin with
Jetpack Compose and **Material 3 (Material You)**. PingLab pings IP addresses and servers,
charts the latency in real time, keeps a history in a local database, and scores the
connection quality with an E-model based MOS estimate.

> Package: `live.nikro.pinglab` - minSdk 26, targetSdk/compileSdk 35, Kotlin 2.0, AGP 8.7.

---

## Features

### Live ping
- Continuous probing with a selectable interval (0.25 s to 5 s) and payload size.
- Five probe protocols: **ICMP**, **TCP connect**, **HTTP**, **HTTPS** and **DNS**.
- Real-time latency chart (line / area / bars), console-style packet log, and nine live
  statistics: last, min, avg, max, median, jitter, loss, p95, MOS.
- Automatic session persistence: a finished run is stored as a session summary.
- Export the run to **CSV** or **JSON** and share it through the Android share sheet.

### Background monitoring
- Add any number of monitored hosts, each with its own interval, timeout, payload,
  failure threshold and "degraded" latency limit.
- A foreground service (`specialUse` FGS type) keeps the checks running with a live
  notification that shows the current up/down summary and a stop action.
- Down / recovered / degraded alerts through a dedicated notification channel.

### History and analysis
- Every sample is written to Room with retention rules (days + max rows per host).
- Per-host detail screen with 1 h / 6 h / 24 h / 7 d windows, latency chart, jitter chart,
  latency histogram, packet-loss donut, quality gauge and a full statistics grid.
- Trend detection (improving / stable / degrading) over the selected window.

### Diagnostics tools
- **Traceroute** built on TTL-limited probes with per-hop RTT bars and export.
- **DNS lookup**: A/AAAA records, CNAME, reverse PTR and resolution timing.
- **TCP port scanner** with a common-ports preset, a 1-1024 sweep or a custom range,
  live progress, service names and banner grabbing.

### Quality scoring
- RFC 3550 jitter, standard deviation, percentiles (p90/p95/p99).
- ITU-T E-model R-factor to MOS conversion, plus per-use-case ratings for gaming,
  voice, video, streaming and browsing.

---

## Material 3 design

The whole UI is Material 3, not Material 2 with new colors:

| Area | Implementation |
| --- | --- |
| Color | Full M3 color-role set (primary / secondary / tertiary containers, surface variants, `surfaceContainer`, `outlineVariant`) for light and dark |
| Dynamic color | `dynamicLightColorScheme` / `dynamicDarkColorScheme` on Android 12+, toggleable in settings |
| Navigation | `NavigationBar` with five destinations, `Scaffold`, `TopAppBar` / `LargeTopAppBar` with `exitUntilCollapsedScrollBehavior` |
| Selection | `SingleChoiceSegmentedButtonRow`, `FilterChip`, `AssistChip`, `PrimaryTabRow` |
| Surfaces | Tonal elevation via `surfaceContainer` roles instead of drop shadows |
| Input | `OutlinedTextField` with supporting text and error state, `Slider`, `Switch`, `ModalBottomSheet` |
| Type / shape | M3 type scale (`displaySmall` to `labelSmall`) and the M3 shape scale |
| System UI | Edge-to-edge with `enableEdgeToEdge()`, dynamic status-bar icon contrast |

Charts are drawn with the Compose `Canvas` API and pull their colors from the same
Material 3 scheme, so they follow the wallpaper theme and dark mode automatically.

---

## Architecture

```
ui/            Compose screens, theme, reusable components and Canvas charts
  navigation/  NavHost + Material 3 NavigationBar
  screens/     dashboard, live, hosts, detail, tools, settings (screen + ViewModel each)
  components/  charts, cards, chips, stat tiles, packet log
  theme/       M3 color roles, typography, shapes, status palette
domain/        stats, quality scoring, MOS, live session, monitor engine
data/          Room database, repositories, DataStore settings, CSV/JSON export
core/          probe engines (ICMP socket, ping binary, TCP, HTTP, DNS), traceroute,
               port scanner, models, formatters, host validation, network inspector
service/       foreground monitoring service, notifications, broadcast actions
di/            ServiceLocator (single, lazy, no reflection)
```

The ICMP path is tiered and falls back automatically:

1. **ICMP datagram socket** (`SOCK_DGRAM`/`IPPROTO_ICMP`) - unprivileged, exact RTT.
2. **`/system/bin/ping`** - output parsed with a pure, unit-tested parser.
3. **`InetAddress.isReachable`** - last resort when the platform blocks both.

See `docs/ARCHITECTURE.md` for the detailed breakdown.

---

## Building

### Android Studio
1. Open the project folder (Android Studio Ladybug or newer).
2. Let Gradle sync; the wrapper is generated on first sync if it is missing.
3. Run the `app` configuration on a device or emulator with API 26+.

### Command line
```bash
./gradlew assembleDebug      # debug APK -> app/build/outputs/apk/debug/
./gradlew test               # JVM unit tests
./gradlew assembleRelease    # minified release build
```

If the wrapper is not present yet:
```bash
gradle wrapper --gradle-version 8.11.1
```

### CI
`.github/workflows/android.yml` runs the unit tests and uploads the debug APK as a
build artifact (`pinglab-debug-apk`) on every push to `main` and `feature/**`.

---

## Permissions

| Permission | Why |
| --- | --- |
| `INTERNET` | sending probes |
| `ACCESS_NETWORK_STATE` | transport type, VPN and metered detection |
| `POST_NOTIFICATIONS` | monitoring notification and alerts (Android 13+) |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE` | background monitoring |
| `RECEIVE_BOOT_COMPLETED` | optional auto-start of monitoring |

No analytics, no ads, no account. All data stays on the device.

---

## Testing

JVM unit tests cover the pure logic:

- `LatencyStatisticsTest` - aggregates, percentiles, jitter, histogram, trend, merge
- `QualityEvaluatorTest` - scoring bounds and use-case grading
- `MosCalculatorTest` - E-model R-factor and MOS behaviour
- `PingOutputParserTest` - every `ping` output shape and failure classification
- `HostValidatorTest` - hostnames, IPv4/IPv6 literals, ports, URL forms
