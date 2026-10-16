package app.hovanki.server.metrics

import app.hovanki.shared.protocol.ApiRoutes
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.math.BigInteger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.time.Duration
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The profile `tls` (application-tls.yaml): the server's own TLS without Caddy in front (docs/live-v2/server.md §2).
 * The certificates are a test CA's (src/test/resources/tls), with EC keys in PKCS#8 as lego v5 writes them; they are
 * copied into a directory of their own, as lego's deploy hook copies them into /etc/hovanki/tls on the VM.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    // The profile's 10 s, shorter: the test waits for the new certificate.
    properties = ["spring.ssl.bundle.watch.file.quiet-period=1s"],
)
@ActiveProfiles("tls")
class TlsProfileTest(@LocalServerPort private val port: Int) {
    @Test
    fun answersOverTlsWithHttp2() {
        val answer = get()
        assertEquals(200, answer.statusCode())
        assertEquals(HttpClient.Version.HTTP_2, answer.version())
    }

    @Test
    fun takesARenewedCertificateWithoutARestart() {
        assertTrue(serialServed() in setOf(FIRST, SECOND))
        install(2)
        val until = System.nanoTime() + Duration.ofSeconds(30).toNanos()
        while (serialServed() != SECOND && System.nanoTime() < until) Thread.sleep(200)
        assertEquals(SECOND, serialServed())
    }

    /** A new client every time: a connection kept from before would show the certificate of its own handshake. */
    private fun get(): HttpResponse<String> {
        val client = HttpClient.newBuilder().sslContext(trustingTheTestCa).version(HttpClient.Version.HTTP_2).build()
        return client.use {
            it.send(
                HttpRequest.newBuilder(URI("https://localhost:$port${ApiRoutes.TIME}")).build(),
                HttpResponse.BodyHandlers.ofString(),
            )
        }
    }

    private fun serialServed(): BigInteger =
        (get().sslSession().orElseThrow().peerCertificates.first() as X509Certificate).serialNumber

    companion object {
        private val FIRST = BigInteger.valueOf(1001)
        private val SECOND = BigInteger.valueOf(1002)
        private val tlsDir: Path = Files.createTempDirectory("hovanki-tls")

        private val trustingTheTestCa: SSLContext by lazy {
            val ca = resource("ca.pem").use { CertificateFactory.getInstance("X.509").generateCertificate(it) }
            val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null, null)
                setCertificateEntry("ca", ca)
            }
            val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
            SSLContext.getInstance("TLS").apply { init(null, trust.trustManagers, null) }
        }

        private fun resource(name: String) = checkNotNull(TlsProfileTest::class.java.getResourceAsStream("/tls/$name"))

        /** As deploy/hovanki-cert-deploy.sh does: each file written next to its place, then moved over it. */
        private fun install(n: Int) {
            for ((from, to) in listOf("key$n.pem" to "privkey.pem", "fullchain$n.pem" to "fullchain.pem")) {
                val next = tlsDir.resolve("$to.next")
                resource(from).use { Files.copy(it, next, StandardCopyOption.REPLACE_EXISTING) }
                Files.move(next, tlsDir.resolve(to), StandardCopyOption.ATOMIC_MOVE)
            }
        }

        @JvmStatic
        @DynamicPropertySource
        fun certificates(registry: DynamicPropertyRegistry) {
            install(1)
            registry.add("HOVANKI_TLS_DIR") { tlsDir.toString() }
        }
    }
}
