package dev.understudy.packaging.axml

/**
 * Rewrites a proxy template's binary `AndroidManifest.xml` so that it claims a different
 * package identity.
 *
 * Three things carry the identity, and all three live in the string pool:
 *
 *  1. the `package` attribute on `<manifest>` — this is what makes the platform treat
 *     `/Android/data/<target>` as *ours*, which is the entire mechanism;
 *  2. `android:authorities` on the bridge provider — kept equal to the package name so a
 *     single substitution retargets it, and so Understudy can predict the authority;
 *  3. fully-qualified component `name` attributes — aapt resolves a leading-dot name
 *     against the manifest package at build time, so by the time we see the AXML these are
 *     absolute (`dev.understudy.proxytpl.bridge.ProxyFileBridge`). They must be rewritten
 *     too, otherwise PackageManager looks for a class that does not exist and the proxy
 *     crashes on first contact.
 *
 * A **prefix** replacement handles all three. Note what it deliberately does *not* touch:
 * the permission name `dev.understudy.permission.BRIDGE`, which must stay identical in the
 * proxy and in Understudy for the `signature`-level grant to line up.
 *
 * The dex is left alone: its class descriptors still say `Ldev/understudy/proxytpl/...`,
 * which is correct and harmless — a class's own package need not match the app's.
 */
object ManifestPatcher {

    /** The template's `applicationId`, i.e. the prefix that gets substituted. */
    const val TEMPLATE_PACKAGE: String = "dev.understudy.proxytpl"

    /**
     * Names that must survive a rename because both sides of the signature-permission
     * handshake have to agree on them.
     */
    private val PRESERVE_SUBSTRINGS = listOf("dev.understudy.permission.")

    class Result(
        val bytes: ByteArray,
        val replacedStrings: List<Pair<String, String>>,
    )

    /**
     * @param manifest the template APK's binary `AndroidManifest.xml`
     * @param targetPackage the package to impersonate
     * @throws IllegalArgumentException if [targetPackage] is not a usable package name
     */
    fun rename(manifest: ByteArray, targetPackage: String): Result {
        requireValidPackageName(targetPackage)

        val doc = AxmlStringPool.parse(manifest)
        val replacements = ArrayList<Pair<String, String>>()

        for (i in doc.strings.indices) {
            val original = doc.strings[i]
            if (!original.startsWith(TEMPLATE_PACKAGE)) continue
            if (PRESERVE_SUBSTRINGS.any { it in original }) continue

            // Swap the prefix, keep the suffix: "" -> package itself,
            // ".bridge.ProxyFileBridge" -> "<target>.bridge.ProxyFileBridge".
            val suffix = original.removePrefix(TEMPLATE_PACKAGE)
            val updated = targetPackage + suffix
            if (updated != original) {
                doc.strings[i] = updated
                replacements += original to updated
            }
        }

        require(replacements.isNotEmpty()) {
            "template manifest contains no '$TEMPLATE_PACKAGE' strings — wrong asset?"
        }
        // The `package` attribute and the provider authority must both have moved; if only
        // one did, the template drifted and the proxy would be unreachable.
        require(replacements.any { it.second == targetPackage }) {
            "rename did not produce a bare '$targetPackage' string (package/authority)"
        }

        val pool = AxmlStringPool.encodePool(doc)
        val total = doc.fileHeaderSize + pool.size + doc.tail.size
        val out = ByteArray(total)
        var p = 0
        out[p++] = (doc.fileType and 0xFF).toByte()
        out[p++] = ((doc.fileType shr 8) and 0xFF).toByte()
        out[p++] = (doc.fileHeaderSize and 0xFF).toByte()
        out[p++] = ((doc.fileHeaderSize shr 8) and 0xFF).toByte()
        putU32(out, p, total); p += 4
        pool.copyInto(out, p); p += pool.size
        doc.tail.copyInto(out, p)

        return Result(out, replacements)
    }

    /**
     * Package names are validated conservatively because a malformed one produces an opaque
     * `INSTALL_FAILED_INVALID_APK` much later in the flow, and because this value comes from
     * user input. Rules mirror what `PackageParser` accepts.
     *
     * Note the deliberate use of ASCII checks rather than `Char.isLetterOrDigit()`: the
     * latter is Unicode-aware, so it happily accepts `com.example.ünïcode`, which the
     * platform then rejects at install time.
     */
    fun requireValidPackageName(name: String) {
        require(name.isNotBlank()) { "package name must not be blank" }
        require(name.length <= 200) { "package name is too long (${name.length} chars)" }
        require(name.all { isAsciiLetterOrDigit(it) || it == '.' || it == '_' }) {
            "package name may only contain ASCII letters, digits, '.' and '_': $name"
        }

        val segments = name.split('.')
        require(segments.size >= 2) { "package name needs at least two segments: $name" }
        for (segment in segments) {
            require(segment.isNotEmpty()) { "package name has an empty segment: $name" }
            require(isAsciiLetter(segment[0]) || segment[0] == '_') {
                "segment '$segment' must start with an ASCII letter or '_': $name"
            }
            require(!segment.all { isAsciiDigit(it) }) {
                "segment '$segment' must not be all digits: $name"
            }
            require(segment !in JAVA_KEYWORDS) {
                "segment '$segment' is a Java keyword and cannot be used: $name"
            }
        }

        val lower = name.lowercase()
        require(lower != "android" && !lower.startsWith("android.")) {
            "'android.*' is reserved for the framework: $name"
        }
        for (reserved in RESERVED_PREFIXES) {
            require(!lower.startsWith(reserved)) {
                "'$reserved' is reserved for system packages: $name"
            }
        }
    }

    /**
     * Namespaces that belong to the platform or to first-party system apps. Installing a
     * proxy under one of these would at best fail and at worst collide with a package that
     * the framework treats specially.
     */
    private val RESERVED_PREFIXES = listOf(
        "com.android.", "com.google.android.", "com.qualcomm.", "com.mediatek.",
        "com.samsung.android.", "com.huawei.android.", "com.miui.",
    )

    private fun isAsciiLetter(c: Char): Boolean = c in 'a'..'z' || c in 'A'..'Z'
    private fun isAsciiDigit(c: Char): Boolean = c in '0'..'9'
    private fun isAsciiLetterOrDigit(c: Char): Boolean = isAsciiLetter(c) || isAsciiDigit(c)

    private fun putU32(b: ByteArray, o: Int, v: Int) {
        b[o] = (v and 0xFF).toByte()
        b[o + 1] = ((v shr 8) and 0xFF).toByte()
        b[o + 2] = ((v shr 16) and 0xFF).toByte()
        b[o + 3] = ((v shr 24) and 0xFF).toByte()
    }

    private val JAVA_KEYWORDS = setOf(
        "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class",
        "const", "continue", "default", "do", "double", "else", "enum", "extends", "final",
        "finally", "float", "for", "goto", "if", "implements", "import", "instanceof", "int",
        "interface", "long", "native", "new", "package", "private", "protected", "public",
        "return", "short", "static", "strictfp", "super", "switch", "synchronized", "this",
        "throw", "throws", "transient", "try", "void", "volatile", "while",
    )
}
