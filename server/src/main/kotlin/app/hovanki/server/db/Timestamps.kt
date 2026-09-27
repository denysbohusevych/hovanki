package app.hovanki.server.db

import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

// timestamptz columns: the PostgreSQL driver reads and writes them as OffsetDateTime (not Instant). Values always
// come from the injected Clock, never from SQL now(), so tests can move time.

fun Instant.toTimestamptz(): OffsetDateTime = atOffset(ZoneOffset.UTC)

fun ResultSet.getInstant(column: String): Instant = getObject(column, OffsetDateTime::class.java).toInstant()

fun ResultSet.getInstantOrNull(column: String): Instant? = getObject(column, OffsetDateTime::class.java)?.toInstant()
