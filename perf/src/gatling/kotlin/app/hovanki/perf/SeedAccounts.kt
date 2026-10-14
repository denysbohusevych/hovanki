package app.hovanki.perf

import app.hovanki.shared.totp.toHex
import java.security.MessageDigest

/**
 * The accounts of the lab's seeded database (perf/seed.sh, perf/postgres/seed.sql, docs/perf.md «Данные как на
 * проде»): account `n` in `0 until users` exists, is verified and not banned, has a session whose token is [token] and
 * logs in with [login] and [PASSWORD]. The formulas are the seed's own: change them together. Its id, for reference,
 * is `"pu" + (n * 7919731 % 10^10)` padded to 10 digits.
 */
object SeedAccounts {
    /** How many accounts the seed made: `-Pperf.seedUsers`, the same as the seed's `-v users` (100 000 by default). */
    val users: Int = LabConfig.int("seedUsers", 100_000)

    /** Everybody's password; the seed stores one BCrypt hash of it (strength 10, as production's). */
    const val PASSWORD = "perf-player-password"

    /**
     * Account [n]'s token for `Authorization: Bearer`: the lowercase hex SHA-256 of `hovanki-perf-user-<n>`, 64 hex
     * characters like the tokens the server issues. The seed stores the SHA-256 of it, as the server does.
     */
    fun token(n: Int): String {
        require(n in 0 until users) { "No seeded account $n: the seed made $users (-Pperf.seedUsers)" }
        return MessageDigest.getInstance("SHA-256").digest("hovanki-perf-user-$n".toByteArray(Charsets.UTF_8)).toHex()
    }

    /** Account [n]'s email, a `LoginRequest.login`. */
    fun login(n: Int): String {
        require(n in 0 until users) { "No seeded account $n: the seed made $users (-Pperf.seedUsers)" }
        return "player$n@hovanki.test"
    }
}
