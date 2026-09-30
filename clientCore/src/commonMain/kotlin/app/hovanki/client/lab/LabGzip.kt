package app.hovanki.client.lab

/**
 * The gzip of [bytes], for the lab's uploads (`Content-Encoding: gzip`); null where the platform has no gzip or it
 * failed: the upload then goes plain.
 */
expect fun gzipOrNull(bytes: ByteArray): ByteArray?
