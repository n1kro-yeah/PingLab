package live.nikro.pinglab.di

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import live.nikro.pinglab.core.net.DnsLookupTool
import live.nikro.pinglab.core.net.PingEngineFactory
import live.nikro.pinglab.core.net.PortScanner
import live.nikro.pinglab.core.net.TlsInspector
import live.nikro.pinglab.core.net.WolSender
import live.nikro.pinglab.core.net.TracerouteEngine
import live.nikro.pinglab.core.util.NetworkInspector
import live.nikro.pinglab.data.db.AppDatabase
import live.nikro.pinglab.data.export.ExportManager
import live.nikro.pinglab.data.prefs.SettingsRepository
import live.nikro.pinglab.data.repo.HostRepository
import live.nikro.pinglab.data.repo.SampleRepository
import live.nikro.pinglab.domain.monitor.LivePingSession

/**
 * Hand-rolled dependency container.
 *
 * A DI framework would add an annotation processor and ~1 s to every build for what amounts
 * to eight singletons; this object is explicit, lazy, and testable (call [override] from a
 * test to swap a repository).
 */
object ServiceLocator {

    private var appContext: Context? = null

    /** Long-lived scope for work that must outlive any screen (seeding, retention sweeps). */
    val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun init(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    val context: Context
        get() = requireNotNull(appContext) {
            "ServiceLocator.init() must be called from Application.onCreate()"
        }

    val database: AppDatabase by lazy { AppDatabase.get(context) }

    val settingsRepository: SettingsRepository by lazy { SettingsRepository(context) }

    val hostRepository: HostRepository by lazy { HostRepository(database.hostDao()) }

    val sampleRepository: SampleRepository by lazy {
        SampleRepository(database.sampleDao(), database.sessionDao())
    }

    val exportManager: ExportManager by lazy { ExportManager(context) }

    /** Shared across the whole process so the DNS cache and ICMP socket are reused. */
    val engineFactory: PingEngineFactory by lazy { PingEngineFactory() }

    val livePingSession: LivePingSession by lazy { LivePingSession(engineFactory) }

    val networkInspector: NetworkInspector by lazy { NetworkInspector(context) }

    val dnsLookupTool: DnsLookupTool by lazy { DnsLookupTool() }

    val portScanner: PortScanner by lazy { PortScanner() }

    val tlsInspector: TlsInspector by lazy { TlsInspector() }

    val wolSender: WolSender by lazy { WolSender() }

    /** Traceroute reuses the ping engines DNS cache through the shared resolver. */
    val tracerouteEngine: TracerouteEngine by lazy { TracerouteEngine(engineFactory.sharedResolver()) }
}
