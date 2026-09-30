package dev.understudy.packaging

import dev.understudy.packaging.axml.AxmlStringPool
import dev.understudy.packaging.axml.ManifestPatcher
import java.io.File
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pins **who defines** `dev.understudy.permission.BRIDGE`, which decides whether the app can talk
 * to its own proxy at all. Two independent defects hide behind this one invariant, and both were
 * found by a device rather than by reading the code.
 *
 * ## Defect 1 — no two proxies could ever coexist
 *
 * The proxy template used to carry `<permission android:name="…BRIDGE" …/>` itself. Since every
 * generated proxy inherits that declaration and each carries a different package name, installing
 * a second proxy failed:
 *
 * ```
 * INSTALL_FAILED_DUPLICATE_PERMISSION: Package com.example.prodgame attempting to redeclare
 *   permission dev.understudy.permission.BRIDGE already owned by com.example.targetgame
 * ```
 *
 * That is not a test-harness artefact. Reaching several targets' save data is the product; a user
 * with two games would hit this on the second one and get an error that names a permission they
 * have never heard of. CI run #28 died here, in under ten seconds, before any test ran.
 *
 * ## Defect 2 — in production the app can never hold the permission
 *
 * A `signature`-level permission is granted to packages signed like whichever package **defines**
 * it. In production the proxy is signed with a per-install key generated on the device, so had the
 * proxy defined BRIDGE, only packages sharing that throwaway key could hold it — and Understudy,
 * signed at build time, never could. The platform enforces a provider's `android:permission`
 * **before any of the provider's code runs**, so every call would be refused at the gate and
 * `ProxyFileBridge.enforceCaller()`'s generator-certificate digest check — written specifically to
 * make production work — would be dead code. A signed release build would be unable to talk to any
 * proxy it made.
 *
 * ## The invariant
 *
 * `:app` defines BRIDGE (so it trivially matches its own certificate and the gate opens), and the
 * proxy only *requires* it on the provider — requiring a permission defined by another package is
 * ordinary Android, resolved device-wide by name. `enforceCaller()` then does the real pinning:
 * the caller must be the exact install that generated the proxy. If `:app` is absent the
 * permission is undefined, and an undefined component permission fails **closed**.
 *
 * Both the sources and the committed binary template are checked. The artifact check is the one
 * that matters — it is the bytes that ship — and it needs no Android classes: `protectionLevel`
 * appears in an AXML string pool only as the attribute of a `<permission>` element, so its absence
 * proves the template defines nothing while `dev.understudy.permission.BRIDGE`'s presence proves
 * the provider still requires it. Element and attribute names share one pool, which is why
 * `permission` alone cannot discriminate.
 */
class BridgePermissionOwnershipTest {

    private val appManifest = File("src/main/AndroidManifest.xml")
    private val proxyManifest = File("../proxy/src/main/AndroidManifest.xml")

    private fun templateApk(): File? = listOf(
        File("src/main/assets/proxy-template.apk"),
        File("../app/src/main/assets/proxy-template.apk"),
    ).firstOrNull { it.isFile }

    private fun templateManifestBytes(): ByteArray {
        val apk = templateApk()
        assertNotNull(apk) {
            "no committed proxy-template.apk; run ./gradlew :app:syncProxyTemplate"
        }
        return ZipFile(apk).use { zip ->
            val entry = zip.getEntry("AndroidManifest.xml")
            assertNotNull(entry, "template APK has no AndroidManifest.xml")
            zip.getInputStream(entry).readBytes()
        }
    }

    /**
     * The source-level rule. Readable, and it names the file to edit when it breaks.
     * `<permission ` with a trailing space matches only the element, never `android:permission=`.
     */
    @Test
    fun `the app defines BRIDGE and the proxy only requires it`() {
        assertTrue(appManifest.isFile, "cannot find ${appManifest.path} (test cwd should be :app)")
        assertTrue(proxyManifest.isFile, "cannot find ${proxyManifest.path}")

        val app = appManifest.readText()
        val proxy = proxyManifest.readText()

        assertTrue(
            Regex("""<permission\s[^>]*android:name="dev\.understudy\.permission\.BRIDGE"""", RegexOption.DOT_MATCHES_ALL)
                .containsMatchIn(app),
            ":app must DEFINE dev.understudy.permission.BRIDGE — if the proxy defines it instead, " +
                "no two proxies can coexist and a signed release cannot hold it",
        )
        assertTrue(
            Regex("""android:protectionLevel="signature"""").containsMatchIn(app),
            "BRIDGE must stay signature-level; a weaker protection level would let any app on the " +
                "device reach a proxy's re-export of another package's private storage",
        )
        assertTrue(
            app.contains("""<uses-permission android:name="dev.understudy.permission.BRIDGE""""),
            ":app must also REQUEST BRIDGE. Depending on a definer being auto-granted its own " +
                "permission is not something to bet a release build on.",
        )

        assertFalse(
            Regex("""<permission\s""").containsMatchIn(proxy),
            ":proxy must NOT define any permission — each generated proxy carries a different " +
                "package name, so a second install dies with INSTALL_FAILED_DUPLICATE_PERMISSION",
        )
        assertTrue(
            proxy.contains("""android:permission="dev.understudy.permission.BRIDGE""""),
            "the provider must still REQUIRE BRIDGE; dropping it would leave the exported provider " +
                "guarded only by enforceCaller()'s digest check, which is absent on test artifacts",
        )
    }

    /** The artifact-level rule, over the bytes that actually ship. */
    @Test
    fun `the committed template requires BRIDGE but defines no permission`() {
        val doc = AxmlStringPool.parse(templateManifestBytes())

        assertTrue(
            "dev.understudy.permission.BRIDGE" in doc.strings,
            "the template no longer references BRIDGE at all — the provider would be unguarded. " +
                "Pool: ${doc.strings}",
        )
        assertFalse(
            "protectionLevel" in doc.strings,
            "the committed proxy template still DEFINES a permission (a <permission> element is " +
                "the only thing that carries android:protectionLevel). Rebuild it: " +
                "./gradlew :app:syncProxyTemplate",
        )
        assertEquals(
            1,
            doc.strings.count { it == ManifestPatcher.TEMPLATE_PACKAGE },
            "the bare template package must appear exactly once (package attribute + provider " +
                "authority share one deduplicated pool entry); a different count means the " +
                "template changed shape and the retargeting assumption is broken",
        )
    }

    /**
     * Retargeting must leave the permission name alone, now that it belongs to `:app`.
     *
     * `ManifestPatcher` substitutes on exact match against the bare template package, so the
     * permission name is spared without a special case — but that is an emergent property of the
     * matching rule, not an explicit one, and it is exactly the kind of thing a plausible future
     * "improvement" (prefix matching, or "also rewrite anything under dev.understudy") would
     * silently break. If it changed, both sides of the handshake would disagree and every call
     * would fail with SecurityException on a device.
     */
    @Test
    fun `retargeting the template preserves the permission name`() {
        val result = ManifestPatcher.rename(templateManifestBytes(), "com.example.targetgame")
        val doc = AxmlStringPool.parse(result.bytes)

        assertTrue(
            "dev.understudy.permission.BRIDGE" in doc.strings,
            "renaming the proxy rewrote the permission name; :app and the proxy would no longer " +
                "agree on it and the signature-level grant could never line up",
        )
        assertFalse(
            "com.example.targetgame.permission.BRIDGE" in doc.strings,
            "the permission name must not follow the package rename",
        )
        assertTrue("com.example.targetgame" in doc.strings, "the package was not retargeted")
        assertFalse(
            "protectionLevel" in doc.strings,
            "a generated proxy defines a permission; two of them cannot be installed at once",
        )
    }

    // ---- the other half: the app must be able to SEE the proxy --------------

    /**
     * Visibility is the failure mode that produced CI run #29, and it is the one that looks least
     * like a permission problem.
     *
     * From API 30 the platform filters package visibility, and the filter covers
     * `ContentResolver` authority resolution — so an app that cannot see the proxy gets
     * `IllegalArgumentException: Unknown authority <target>`, which is indistinguishable from "no
     * proxy is installed". Run #29 failed exactly that way on both API levels, five tests down,
     * while `adb shell content query --user 10 --uri content://com.example.targetgame/data`
     * returned rows and `content call … --method ping` returned `ok=true` in the same log. The
     * shell is not filtered; the app is.
     *
     * Until then the bridge had worked without any `<queries>` declaration, by accident: the proxy
     * *defined* `BRIDGE` and the app *requested* it, and AOSP `AppsFilter.addPackageInternal`
     * populates `mQueryableViaUsesPermission` so that a package becomes visible to anything
     * requesting a permission it defines. Moving the definition to `:app` — mandatory, see above —
     * silently deleted that visibility.
     *
     * What replaces it has to be a *static* declaration, because the target package name is chosen
     * when the APK is generated: `<queries><package>` cannot name it, `<queries><provider>` cannot
     * either (the authority is the package name), and a per-target permission the proxy defines
     * cannot be requested statically. `<queries><intent>` is the only mechanism that matches a set
     * of packages whose names are unknown at build time, so every proxy advertises
     * [dev.understudy.bridge.BridgeContract.DISCOVERY_ACTION] and the app queries for it.
     * `QUERY_ALL_PACKAGES` would also work and is deliberately not used.
     */
    @Test
    fun theTemplateAdvertisesTheDiscoveryActionAndTheAppQueriesForIt() {
        val action = dev.understudy.bridge.BridgeContract.DISCOVERY_ACTION

        // Built by concatenation rather than as a raw string: a Kotlin raw string cannot contain
        // the `"` that closes an XML attribute value adjacent to its own `"""` delimiter.
        val attr = "android:name=\"" + action + "\""

        // 1. the app declares <queries> for it
        val app = appManifest.readText()
        val queries = app.substringAfter("<queries>", "").substringBefore("</queries>", "")
        assertTrue(
            queries.isNotBlank() && queries.contains(attr),
            ":app must declare <queries><intent> for $action, or the proxies it generates are " +
                "invisible to it and every bridge call fails with 'Unknown authority'",
        )

        // 2. the proxy source advertises it
        val proxy = proxyManifest.readText()
        assertTrue(
            proxy.contains(attr),
            ":proxy must advertise $action on a component, or <queries> matches nothing",
        )
        assertTrue(
            proxy.contains(".ProxyDiscoveryReceiver"),
            "the discovery action must live on ProxyDiscoveryReceiver, a component nothing ever " +
                "disables — putting it on ProxyStatusActivity would make the proxy unreachable " +
                "the moment KEEP_HIDDEN teardown hides it, which is when it must still be callable",
        )

        // 3. and so does the committed binary template — the bytes that actually ship
        val doc = AxmlStringPool.parse(templateManifestBytes())
        assertTrue(
            action in doc.strings,
            "the committed template does not advertise $action; rebuild it with " +
                "./gradlew :app:syncProxyTemplate. Pool: ${doc.strings}",
        )
        assertTrue(
            "${ManifestPatcher.TEMPLATE_PACKAGE}.ProxyDiscoveryReceiver" in doc.strings,
            "the discovery receiver is missing from the committed template",
        )
        assertTrue("receiver" in doc.strings, "no <receiver> element in the committed template")
    }

    /**
     * Retargeting must leave the discovery action and the receiver's class name alone — the same
     * rule that already protects `ProxyFileBridge`'s class name, and for the same reason: the dex
     * keeps `Ldev/understudy/proxytpl/ProxyDiscoveryReceiver;`, so a rewritten manifest name makes
     * PackageManager look for a class that does not exist.
     *
     * A rewritten *action* would be quieter and worse: the proxy would install and run, the app's
     * `<queries>` would simply stop matching it, and the symptom would again be "Unknown authority".
     */
    @Test
    fun retargetingPreservesTheDiscoveryActionAndReceiverClassName() {
        val action = dev.understudy.bridge.BridgeContract.DISCOVERY_ACTION
        val doc = AxmlStringPool.parse(
            ManifestPatcher.rename(templateManifestBytes(), "com.example.targetgame").bytes,
        )

        assertTrue(action in doc.strings, "retargeting rewrote the discovery action")
        assertTrue(
            "${ManifestPatcher.TEMPLATE_PACKAGE}.ProxyDiscoveryReceiver" in doc.strings,
            "retargeting rewrote the receiver's class name; the dex still has the old one",
        )
        assertFalse(
            "com.example.targetgame.ProxyDiscoveryReceiver" in doc.strings,
            "component names must not follow the package rename",
        )
    }

    /**
     * Guards the guard: if `protectionLevel` ever stopped being the discriminator this whole test
     * class would pass vacuously. Assert it is present in a manifest that *does* define the
     * permission — `:app`'s own — so the negative assertion above has meaning.
     */
    @Test
    fun `protectionLevel really is the discriminator for a permission definition`() {
        assertTrue(
            appManifest.readText().contains("protectionLevel"),
            ":app's manifest no longer mentions protectionLevel, so the artifact-level assertion " +
                "in this class has lost its meaning — rewrite the discriminator",
        )
    }
}
