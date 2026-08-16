# PingLab architecture

## Layers

```
+-------------------------------------------------------------+
|  ui/          Compose + Material 3 (screens, charts, theme)  |
+-------------------------------------------------------------+
|  domain/      statistics, quality scoring, session, monitor  |
+-------------------------------------------------------------+
|  data/        Room, DataStore, repositories, export          |
+-------------------------------------------------------------+
|  core/        probe engines, models, utils                   |
+-------------------------------------------------------------+
|  service/     foreground service, notifications              |
+-------------------------------------------------------------+
```

Dependencies point downwards only. `ui` never touches `core/net` directly - it goes
through `domain` (live session, monitor engine) or `data` (repositories).

## Dependency injection

`di/ServiceLocator` is a single object with lazily created singletons: database,
repositories, engine factory, live session, network inspector, DNS tool, port scanner,
traceroute engine and export manager. It is initialised from `PingLabApplication` and
again (defensively) from `MainActivity`. No reflection, no annotation processing for DI,
which keeps the build fast and the startup path obvious.

## Probe engines

`core/net/PingEngine.kt` defines the contract:

```kotlin
interface PingEngine {
    suspend fun probe(request: ProbeRequest): ProbeResult
}
```

Implementations:

| Engine | Transport | Notes |
| --- | --- | --- |
| `IcmpPingEngine` | ICMP | tiered: datagram socket -> system binary -> `isReachable` |
| `IcmpDatagramPinger` | ICMP | `SOCK_DGRAM` + `IPPROTO_ICMP`, manual echo header and checksum |
| `SystemPingBinary` | ICMP | runs `/system/bin/ping`, kills the process on timeout |
| `TcpPingEngine` | TCP | connect-time measurement with `Socket.connect(timeout)` |
| `HttpPingEngine` | HTTP(S) | DNS / connect / TLS / first-byte split timings |
| `DnsPingEngine` | DNS | UDP query round trip against a resolver |

`AddressResolver` caches resolutions with their own timing so DNS cost never pollutes the
measured RTT.

### Why the tiered ICMP path

Android does not allow raw sockets to unprivileged apps. Since Android 5 the kernel
supports unprivileged ICMP datagram sockets, but some OEM kernels and some networks block
them. PingLab therefore probes the first tier, caches the tier that works
(`@Volatile preferredTier`), and only falls back when the failure is environmental
(`ProbeStatus.isEnvironmental`), never when the host itself is simply down.

## Statistics and scoring

`domain/stats/LatencyStatistics` computes min / avg / max / median / p90 / p95 / p99,
standard deviation, mean-deviation jitter and RFC 3550 jitter:

```
J(i) = J(i-1) + (|D(i-1, i)| - J(i-1)) / 16
```

`domain/quality/MosCalculator` applies the ITU-T G.107 E-model:

```
effectiveLatency = avgRtt / 2 + jitter * 2 + 10
R  = 93.2 - effectiveLatency / 40                 (effectiveLatency < 160 ms)
R  = 93.2 - (effectiveLatency - 120) / 10         (otherwise)
R -= loss * 2.5
MOS = 1 + 0.035 * R + R * (R - 60) * (100 - R) * 7.10e-6      clamped to 1.0 .. 4.41
```

`QualityEvaluator` blends loss (45%), latency (30%) and jitter (25%) into a 0-100 score
with grade cutoffs at 90 / 75 / 55 / 35, then derives per-use-case ratings.

## Data

- **Room** (`pinglab.db`, v1): `hosts`, `samples`, `sessions`, plus aggregate queries
  (`HostAggregate`, `HistoryBucket`) so long windows never load every row into memory.
- **DataStore Preferences** (`pinglab_settings`) holds `AppSettings`, exposed as a `Flow`.
- `SampleRepository` batches inserts and applies retention (age + max rows per host).
- `ExportManager` writes CSV/JSON into `cacheDir/exports` and shares them through a
  `FileProvider`.

## Background monitoring

`PingMonitorService` is a foreground service (`specialUse`) that owns a `MonitorEngine`.
The engine keeps one coroutine per enabled host, publishes a `StateFlow<Map<Long, HostSnapshot>>`
and emits alerts through a `SharedFlow`. The dashboard binds to the same snapshots, so
the UI and the notification always agree. Host state changes to `DOWN` only after
`failureThreshold` consecutive failures, which prevents alert storms on flaky Wi-Fi.

## UI

Each screen is a `@Composable` plus a `ViewModel` that exposes a single immutable
`UiState` through `StateFlow`, collected with `collectAsStateWithLifecycle()`.

Charts (`ui/components/Charts.kt`) are pure Compose `Canvas` drawings: latency line/area,
sparkline, histogram, jitter bars, loss donut, quality gauge and hop bars. No third-party
chart dependency, which keeps the APK small and the theming perfectly consistent with the
Material 3 color scheme.

## Threading

- Probe engines run on `Dispatchers.IO`.
- Statistics are computed on `Dispatchers.Default` inside the ViewModels.
- The monitor engine uses its own `SupervisorJob` scope so one failing host cannot cancel
  the others.
