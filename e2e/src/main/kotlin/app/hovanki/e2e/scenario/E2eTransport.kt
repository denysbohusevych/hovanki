package app.hovanki.e2e.scenario

/**
 * How the bots sync in this run (docs/e2e.md, docs/adr/0015-websockets.md): like the app, the live channel whenever the
 * server has it on, else polling. `-Pe2e.transport=socket` turns the live channel on for every scenario, so the whole
 * suite plays over sockets; by default it stays off, as on a server whose operator has not switched it on, and only the
 * scenarios about it (`LiveSocketTest`) use it.
 */
object E2eTransport {
    val isSocket: Boolean = System.getProperty("hovanki.e2e.transport") == "socket"

    val name: String get() = if (isSocket) "socket" else "polling"
}
