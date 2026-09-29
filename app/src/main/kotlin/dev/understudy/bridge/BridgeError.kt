package dev.understudy.bridge

/**
 * Why a bridge operation failed.
 *
 * Modelled as a sealed type rather than throwing platform exceptions because the failure modes
 * here are *expected* and each needs different UI: "proxy not installed" is a call to action,
 * "path rejected" is a bug report, and "protocol mismatch" means "regenerate and reinstall".
 * Collapsing them into `Exception` would lose exactly the distinction the user needs.
 */
sealed class BridgeError(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** No provider answers at that authority: the proxy is not installed for this user. */
    class ProxyUnreachable(val authority: String, cause: Throwable) : BridgeError(
        "No proxy responds at authority '$authority'. Is it installed in this user profile?",
        cause,
    )

    /**
     * The provider exists but refused us. Almost always the `signature`-level
     * `dev.understudy.permission.BRIDGE` not being held — which happens if the proxy was signed
     * with a different key than this install of Understudy (e.g. the signing identity was
     * regenerated, or a proxy from another device was sideloaded).
     */
    class PermissionDenied(val authority: String, cause: Throwable) : BridgeError(
        "The proxy at '$authority' refused access. It was probably signed with a different key.",
        cause,
    )

    /** The proxy answered but reports a protocol version we do not speak. */
    class ProtocolMismatch(val theirs: Int, val ours: Int) : BridgeError(
        "Proxy speaks protocol v$theirs but this app speaks v$ours. Regenerate and reinstall it.",
    )

    /** The proxy is installed but its package name is not the one we asked for. */
    class WrongIdentity(val expected: String, val actual: String) : BridgeError(
        "The proxy at '$actual' is not impersonating '$expected'.",
    )

    /** Our own path validation rejected the request before it left the process. */
    class InvalidPath(val path: String, reason: String) : BridgeError(
        "Refusing path '$path': $reason",
    )

    /** The proxy validated the path and still refused it. */
    class RejectedByProxy(val path: String, reason: String) : BridgeError(
        "Proxy rejected '$path': $reason",
    )

    /** Entry does not exist. Distinct from I/O failure so "refresh" is not shown as an error. */
    class NotFound(val path: String) : BridgeError("No such entry: $path")

    class Io(val path: String, cause: Throwable) : BridgeError(
        "I/O failed on '$path': ${cause.message}", cause,
    )

    /** Storage not mounted, or the root directory could not be resolved by the proxy. */
    class RootUnavailable(val root: String) : BridgeError(
        "The proxy could not resolve root '$root'. Is external storage mounted?",
    )

    /** The proxy returned `ok=false` with a reason we do not otherwise classify. */
    class OperationFailed(val operation: String, val reason: String) : BridgeError(
        "$operation failed: $reason",
    )

    companion object {
        /**
         * Maps the two exceptions the platform throws when a provider cannot be reached.
         *
         * `IllegalArgumentException` ("Unknown authority") means nothing is installed there.
         * `SecurityException` means something *is* installed but will not talk to us — a very
         * different situation, and one that specifically suggests a signing-key mismatch.
         */
        fun fromLookup(authority: String, t: Throwable): BridgeError = when (t) {
            is SecurityException -> PermissionDenied(authority, t)
            is IllegalArgumentException -> ProxyUnreachable(authority, t)
            else -> OperationFailed("contacting $authority", "${t.javaClass.simpleName}: ${t.message}")
        }
    }
}
