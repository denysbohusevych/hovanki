package app.hovanki.client.lab

/**
 * What the OS lets this app do, by name (`app.hovanki.shared.lab.PermFields`), for the field log's `perm` event
 * (docs/adr/0018-field-test-build.md §3.2): where the player's choices of the phone's settings show. Only words, no
 * numbers: `always` / `when_in_use` / `denied`, `on` / `off`. Main thread, cheap: [FieldSession] asks every half
 * minute while the log writes.
 */
fun interface AppPermissions {
    /** The permissions the platform knows, now; a name the platform has no notion of is left out. */
    fun states(): Map<String, String>

    object None : AppPermissions {
        override fun states(): Map<String, String> = emptyMap()
    }
}
