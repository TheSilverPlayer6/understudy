package dev.understudy.core.model

/**
 * Which of the two app-specific external trees an operation targets.
 *
 * They are separate roots rather than one tree because the platform creates and manages them
 * independently, and because users think of them separately: save data versus expansion packs.
 */
enum class StorageRoot(val wireName: String, val displayName: String) {
    /** `Android/data/<pkg>` — in-app save data, caches, external files. */
    DATA("data", "Android/data"),

    /** `Android/obb/<pkg>` — expansion packs. Often multi-GB, hence streamed everywhere. */
    OBB("obb", "Android/obb"),
    ;

    companion object {
        fun fromWire(value: String): StorageRoot? = entries.firstOrNull { it.wireName == value }
    }
}

/**
 * A single entry reported by the proxy.
 *
 * [relativePath] is slash-separated and relative to the root, never absolute and never
 * containing `..` — the proxy validates that before it ever reaches us, and we validate again
 * before sending anything back, because the two processes only share a signing key.
 */
data class RemoteEntry(
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val lastModifiedMillis: Long,
    val canRead: Boolean,
    val canWrite: Boolean,
    val relativePath: String,
)

/** Per-root status returned by the proxy's `ping`. */
data class RootStatus(
    val root: StorageRoot,
    val exists: Boolean,
    val absolutePath: String?,
    val usableBytes: Long,
)

/**
 * Proof that a working proxy is reachable, and in which profile.
 *
 * [userId] comes from the proxy's own uid, so it is the *proxy's* user, not ours. Comparing it
 * against our own user id is how the UI can tell the user "this is running in the profile you
 * think it is" — which matters enormously when the whole point of the app is secondary users.
 */
data class BridgeInfo(
    val packageName: String,
    val userId: Int,
    val protocolVersion: Int,
    val roots: List<RootStatus>,
) {
    val protocolCompatible: Boolean get() = protocolVersion == PROTOCOL_VERSION

    companion object {
        const val PROTOCOL_VERSION: Int = 1
    }
}

/** Aggregate size of a subtree, for showing the user what they are about to copy. */
data class TreeStat(
    val entryCount: Long,
    val totalBytes: Long,
)

/**
 * The thing the user actually cares about: an app whose private storage should become
 * reachable, in a particular profile.
 */
data class ProxyTarget(
    val packageName: String,
    /** Android user id the proxy must be installed into. 0 is the owner. */
    val userId: Int,
    /** Free-form label for the UI; never used for anything security-relevant. */
    val label: String? = null,
)
