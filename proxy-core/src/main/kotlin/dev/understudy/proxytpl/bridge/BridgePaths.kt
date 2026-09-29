package dev.understudy.proxytpl.bridge

import java.io.File

/**
 * Resolves and validates paths inside the proxy's own app-specific external directories.
 *
 * Because the proxy *is* the target package for this user, the platform grants it full
 * read/write access here — this is the entire trick. Nothing outside these two roots is
 * ever reachable, and the validation below is what makes that a hard guarantee rather
 * than an aspiration: the provider is exported, so a hostile caller with our signing key
 * (or any future bug in Understudy) must still not be able to walk out of the sandbox.
 *
 * Kept free of Android dependencies except [android.os.Environment] behind [RootResolver]
 * so that the traversal rules are unit-testable on the JVM.
 */
internal object BridgePaths {

    /** Supplies the two root directories; injectable for tests. */
    fun interface RootResolver {
        fun resolve(root: String): File?
    }

    /** A path that failed validation, with a reason suitable for surfacing to the caller. */
    sealed class Resolution {
        data class Ok(val file: File, val root: File, val relative: String) : Resolution()
        data class Rejected(val reason: String) : Resolution()
    }

    /**
     * Rejects anything that could escape the root:
     *  - null bytes (classic truncation attack against native path handling)
     *  - absolute paths
     *  - `..` in any segment, even when it would resolve back inside the root
     *  - backslashes, which some filesystem layers normalise to `/`
     *  - an unknown root name
     *
     * It then additionally verifies via canonical paths that the result really is inside
     * the root, so symlinks placed *inside* the tree by another process cannot redirect us.
     */
    fun resolve(
        resolver: RootResolver,
        root: String,
        relativePath: String,
    ): Resolution {
        if (root !in BridgeContract.ROOTS) {
            return Resolution.Rejected("unknown root: $root")
        }

        val reason = validateRelative(relativePath)
        if (reason != null) return Resolution.Rejected(reason)

        val rootDir = resolver.resolve(root)
            ?: return Resolution.Rejected("root unavailable: $root (storage not mounted?)")

        val segments = relativePath.split('/').filter { it.isNotEmpty() }
        val target = segments.fold(rootDir) { acc, seg -> File(acc, seg) }

        val canonicalRoot = runCatching { rootDir.canonicalFile }.getOrNull()
            ?: return Resolution.Rejected("cannot canonicalise root $root")
        val canonicalTarget = runCatching { target.canonicalFile }.getOrNull()
            ?: return Resolution.Rejected("cannot canonicalise target")

        if (canonicalTarget != canonicalRoot &&
            !canonicalTarget.path.startsWith(canonicalRoot.path + File.separator)
        ) {
            return Resolution.Rejected("path escapes root: $relativePath")
        }

        val relative = canonicalTarget.path
            .removePrefix(canonicalRoot.path)
            .removePrefix(File.separator)
        return Resolution.Ok(canonicalTarget, canonicalRoot, relative)
    }

    /** Returns null when [relativePath] is acceptable, otherwise the rejection reason. */
    fun validateRelative(relativePath: String): String? {
        if (relativePath.isEmpty()) return null
        if (relativePath.indexOf('\u0000') >= 0) return "path contains NUL"
        if (relativePath.startsWith("/")) return "path must be relative"
        if (relativePath.contains('\\')) return "path must not contain backslashes"
        for (segment in relativePath.split('/')) {
            if (segment == "..") return "path must not contain '..'"
        }
        // Disallow trailing garbage like "a//b" resolving oddly, but permit a trailing slash.
        if (relativePath.contains("//")) return "path must not contain empty segments"
        return null
    }

    /** Joins a relative path onto a root, for building child listings. */
    fun child(relative: String, name: String): String =
        if (relative.isEmpty()) name else "$relative/$name"
}
