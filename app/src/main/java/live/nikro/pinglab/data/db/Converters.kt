package live.nikro.pinglab.data.db

import androidx.room.TypeConverter
import live.nikro.pinglab.core.model.ProbeStatus
import live.nikro.pinglab.core.model.ProbeTransport
import live.nikro.pinglab.core.model.Protocol
import live.nikro.pinglab.core.model.QualityGrade

/**
 * Enums are stored by name rather than ordinal.
 *
 * Ordinals are compact but brittle: inserting a new constant in the middle of an enum would
 * silently reinterpret every historical row. Names cost a few bytes and survive refactors,
 * and unknown values decay to a safe default instead of crashing.
 */
class Converters {

    @TypeConverter
    fun protocolToString(value: Protocol): String = value.name

    @TypeConverter
    fun stringToProtocol(value: String?): Protocol =
        Protocol.entries.firstOrNull { it.name == value } ?: Protocol.ICMP

    @TypeConverter
    fun statusToString(value: ProbeStatus): String = value.name

    @TypeConverter
    fun stringToStatus(value: String?): ProbeStatus =
        ProbeStatus.entries.firstOrNull { it.name == value } ?: ProbeStatus.ERROR

    @TypeConverter
    fun transportToString(value: ProbeTransport): String = value.name

    @TypeConverter
    fun stringToTransport(value: String?): ProbeTransport =
        ProbeTransport.entries.firstOrNull { it.name == value } ?: ProbeTransport.NONE

    @TypeConverter
    fun gradeToString(value: QualityGrade): String = value.name

    @TypeConverter
    fun stringToGrade(value: String?): QualityGrade =
        QualityGrade.entries.firstOrNull { it.name == value } ?: QualityGrade.UNKNOWN
}
