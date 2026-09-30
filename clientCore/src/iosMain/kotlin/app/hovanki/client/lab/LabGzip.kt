@file:OptIn(ExperimentalForeignApi::class)

package app.hovanki.client.lab

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import platform.zlib.ZLIB_VERSION
import platform.zlib.Z_DEFAULT_COMPRESSION
import platform.zlib.Z_DEFAULT_STRATEGY
import platform.zlib.Z_DEFLATED
import platform.zlib.Z_FINISH
import platform.zlib.Z_OK
import platform.zlib.Z_STREAM_END
import platform.zlib.deflate
import platform.zlib.deflateBound
import platform.zlib.deflateEnd
import platform.zlib.deflateInit2_
import platform.zlib.z_stream

// Written without a Mac (docs/radar-run.md step 1): not compiled on Linux, where the iOS targets are skipped. If it
// fails to build or returns null, the uploads simply go plain.

/** zlib's window bits plus 16: a gzip header and trailer instead of zlib's. */
private const val GZIP_WINDOW_BITS = 15 + 16
private const val MEMORY_LEVEL = 8
private const val GZIP_SLACK = 32

actual fun gzipOrNull(bytes: ByteArray): ByteArray? {
    if (bytes.isEmpty()) return null
    memScoped {
        val stream = alloc<z_stream>()
        val init = deflateInit2_(
            stream.ptr,
            Z_DEFAULT_COMPRESSION,
            Z_DEFLATED,
            GZIP_WINDOW_BITS,
            MEMORY_LEVEL,
            Z_DEFAULT_STRATEGY,
            ZLIB_VERSION,
            sizeOf<z_stream>().toInt(),
        )
        if (init != Z_OK) return null
        try {
            // The bound covers zlib's wrapper; a few bytes more for the gzip header all the same.
            val out = ByteArray(deflateBound(stream.ptr, bytes.size.convert()).toInt() + GZIP_SLACK)
            bytes.usePinned { input ->
                out.usePinned { output ->
                    stream.next_in = input.addressOf(0).reinterpret<UByteVar>()
                    stream.avail_in = bytes.size.convert()
                    stream.next_out = output.addressOf(0).reinterpret<UByteVar>()
                    stream.avail_out = out.size.convert()
                    if (deflate(stream.ptr, Z_FINISH) != Z_STREAM_END) return null
                }
            }
            return out.copyOf(stream.total_out.toInt())
        } finally {
            deflateEnd(stream.ptr)
        }
    }
}
