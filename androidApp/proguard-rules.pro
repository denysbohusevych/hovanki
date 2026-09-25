# R8 rules for the release build. Libraries ship their own consumer rules (kotlinx.serialization,
# Koin, Compose, Play Services); below are only the warnings R8 raises about optional classes.

# Ktor: optional SLF4J logging backend and JVM-only debug helpers that don't exist on Android.
-dontwarn org.slf4j.**
-dontwarn java.lang.management.ManagementFactory
-dontwarn java.lang.management.RuntimeMXBean
-dontwarn io.ktor.util.debug.**

# OkHttp: optional TLS providers picked up via reflection when present.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
