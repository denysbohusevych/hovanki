package app.hovanki.server.lab

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.util.unit.DataSize
import java.time.Duration

/** `hovanki.lab.*`: the radio lab's runs (docs/adr/0017-radar-techniques-and-big-run.md §5, §7). */
@ConfigurationProperties("hovanki.lab")
data class LabProperties(
    /** A run's logs (its chunks) are deleted this long after its end (DataRetention); the run and its report stay. */
    val chunkRetention: Duration = Duration.ofDays(90),
    /** Phones join a run, and upload to it, until this long after it was made: a run is for one day outdoors. */
    val joinWindow: Duration = Duration.ofHours(24),
    /** A finished run still takes the phones' last uploads this long: they flush what they logged until the end. */
    val uploadGrace: Duration = Duration.ofMinutes(30),
    /** Devices per run, rejoins of the same phone included. */
    val maxDevices: Int = 8,
    /** What a run's chunks may take on the server, gzipped as they are stored. */
    val maxRunBytes: DataSize = DataSize.ofMegabytes(300),
    /** One upload, uncompressed. */
    val maxChunkBytes: DataSize = DataSize.ofMegabytes(4),
    /**
     * The most events of a run the server's report reads (all its devices'): about 200 bytes of heap each while it is
     * computed. A bigger run's report says so; its raw logs are merged on a computer (`LabCli merge`).
     */
    val maxReportEvents: Long = 500_000,
    /** The live view keeps the readings of this long in memory. */
    val liveWindow: Duration = Duration.ofSeconds(30),
)
