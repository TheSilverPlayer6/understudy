package dev.understudy.packaging.axml

/**
 * Rewrites a proxy template's binary `AndroidManifest.xml` so that it claims a different
 * package identity.
 *
 * Exactly **two** strings carry the identity, and both are the bare package name:
 *
 *  1. the `package` attribute on `<manifest>` — this is what makes the platform treat
 *     `/Android/data/<target>` as *ours*, which is the entire mechanism;
 *  2. `android:authorities` on the bridge provider — deliberately equal to the package name, so
 *     one exact-match substitution retargets both and Understudy can always predict the
 *     authority.
 *
 * Everything else is left alone, and that includes the **fully-qualified component `name`
 * attributes**. This is counter-intuitive and was gotten wrong once, with a JVM test suite that
 * passed while asserting the wrong behaviour:
 *
 * aapt does resolve a leading-dot component name against the manifest package at build time, so
 * by the time we see the AXML the name is absolute
 * (`dev.understudy.proxytpl.bridge.ProxyFileBridge`). It is tempting to conclude that renaming
 * the package means those must be renamed too. They must not: a component class's own package has
 * no obligation to match the application's package. The dex still contains
 * `Ldev/understudy/proxytpl/bridge/ProxyFileBridge;`, so rewriting the manifest name makes
 * PackageManager look for a class that does not exist and the proxy dies at process start with
 *
 * ```
 * RuntimeException: Unable to get provider com.example.targetgame.bridge.ProxyFileBridge:
 *   ClassNotFoundException: Didn't find class "com.example.targetgame.bridge.ProxyFileBridge"
 * ```
 *
 * Only a real device shows this. Robolectric registers the provider by class reference, so it
 * never resolves the name through PackageManager, and the string-pool tests were asserting the
 * buggy expectation.
 *
 * So the rule is **exact match only**: replace a pool string iff it *equals* the template
 * package. That naturally spares the component names, and it spares
 * `dev.understudy.permission.BRIDGE` — which must NOT follow the rename, because `:app` defines
 * that one name and every generated proxy requires it on its provider, so rewriting it per target
 * would leave each proxy guarded by a permission nothing defines (and an undefined component
 * permission fails closed, i.e. the bridge would be unreachable). It also spares
 * `dev.understudy.action.PROXY_DISCOVERY`, which is what lets `:app`'s `<queries><intent>` find
 * proxies whose package names are not known until generation time.
 *
 * `BridgePermissionOwnershipTest` pins all three on the committed binary template, not just on the
 * sources, so a future "improvement" to the matching rule fails on the JVM rather than as an
 * unreachable proxy on a device.
 */
object ManifestPatcher {

    /** The template's `applicationId`, i.e. the string that gets substituted. */
    const val TEMPLATE_PACKAGE: String = "dev.understudy.proxytpl"

    /**
     * How many pool entries the bare package name occupies in the template manifest: **one**.
     *
     * Not two, which is what you would guess from there being two attributes that use it
     * (`<manifest package>` and the provider's `android:authorities`). The AXML string pool is
     * deduplicated, so both attributes hold the same string *index* and a single substitution
     * retargets both — which is exactly why the authority is set to `${applicationId}` in the
     * template manifest.
     *
     * If this count ever changes, the template drifted and the proxy would install under an
     * identity the app cannot predict, so `rename` fails loudly instead.
     */
    const val EXPECTED_IDENTITY_STRINGS: Int = 1

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

        var replaced = 0
        for (i in doc.strings.indices) {
            // EXACT match, not prefix. See the class documentation: a prefix substitution also
            // rewrites fully-qualified component names, and those classes do not move in the
            // dex, so the proxy would fail to start with a ClassNotFoundException.
            if (doc.strings[i] != TEMPLATE_PACKAGE) continue
            if (targetPackage == TEMPLATE_PACKAGE) continue
            doc.strings[i] = targetPackage
            replacements += TEMPLATE_PACKAGE to targetPackage
            replaced++
        }

        // Fail here rather than emitting an APK that only a device will reject: a wrong count
        // means the template drifted and the proxy would install under an identity the app
        // cannot predict, or not be reachable at all.
        require(replaced > 0) {
            "template manifest contains no bare '$TEMPLATE_PACKAGE' string — wrong asset?"
        }
        require(replaced == EXPECTED_IDENTITY_STRINGS) {
            "expected the bare package name to appear $EXPECTED_IDENTITY_STRINGS times " +
                "(package attribute + provider authority) but found $replaced. The template " +
                "manifest changed shape; re-check which attributes reference it."
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
