import ComposeApp
import CryptoKit
import Foundation

/// AES-128-GCM for the live channel v2's UDP (docs/live-v2/client.md §6): Kotlin/Native can't call CryptoKit, so it
/// calls this through the protocol `LiveCryptoBridge` of `ComposeApp`. `ContentView` installs it before the app starts;
/// Kotlin checks it against the spec's known answer first and offers UDP only when it passes. Stateless: the key comes
/// with every call, from any thread.
final class LiveCrypto: NSObject, LiveCryptoBridge {
    private static let tagBytes = 16

    /// The ciphertext followed by the 16-byte tag; nil when CryptoKit refuses (a key or a nonce of the wrong size).
    func seal(key: Data, nonce: Data, aad: Data, plaintext: Data) -> Data? {
        guard let iv = try? AES.GCM.Nonce(data: nonce),
              let box = try? AES.GCM.seal(plaintext, using: SymmetricKey(data: key), nonce: iv, authenticating: aad)
        else { return nil }
        return box.ciphertext + box.tag
    }

    /// The plaintext; nil when the datagram doesn't authenticate.
    func open(key: Data, nonce: Data, aad: Data, sealed: Data) -> Data? {
        guard sealed.count >= Self.tagBytes,
              let iv = try? AES.GCM.Nonce(data: nonce),
              let box = try? AES.GCM.SealedBox(
                  nonce: iv,
                  ciphertext: sealed.dropLast(Self.tagBytes),
                  tag: sealed.suffix(Self.tagBytes)
              )
        else { return nil }
        return try? AES.GCM.open(box, using: SymmetricKey(data: key), authenticating: aad)
    }
}
