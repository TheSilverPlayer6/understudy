package dev.understudy.instrumented

import android.content.Context
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.understudy.bridge.BridgeClient
import dev.understudy.bridge.BridgeError
import dev.understudy.core.model.StorageRoot
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The premise test. Everything else in this project is downstream of one claim:
 *
 * > A proxy APK carrying package name P, installed for user N, can read and write
 * > `/storage/emulated/N/Android/{data,obb}/P` — and can hand those bytes to another app.
 *
 * That claim is about the **kernel's FUSE layer**, so it cannot be tested on a JVM. Robolectric
 * has no FUSE: `BridgeIntegrationTest` proves the protocol is correct but says nothing about
 * whether the platform grants the access. These tests close that gap and must run on a real
 * device or emulator, inside the secondary profile.
 *
 * CI orchestration (`.github/workflows/emulator.yml`) creates user 10, generates a proxy APK,
 * installs it *and* this app for user 10, plants known bytes in the proxy's private storage via
 * `adb shell` (which is exempt from the FUSE filter), then runs this class with
 * `am instrument --user 10`.
 *
 * Arguments:
 *  - `targetPackage` (required) — the package the installed proxy impersonates
 *  - `userId`        (optional) — expected Android user id, cross-checked against the proxy's
 *  - `expectPlanted` (optional, default true) — whether CI planted the fixture files
 */
@RunWith(AndroidJUnit4::class)
class BridgePremiseTest {

    private lateinit var context: Context
    private lateinit var target: String
    private var expectedUser: Int = -1
    private var expectPlanted: Boolean = true

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val args = InstrumentationRegistry.getArguments()
        target = args.getString("targetPackage") ?: ""
        expectedUser = args.getString("userId")?.toIntOrNull() ?: -1
        expectPlanted = args.getString("expectPlanted")?.toBoolean() ?: true

        // Without a target there is nothing to assert; skip rather than fail so that this class
        // can live in the repo without breaking an ordinary `connectedCheck` run.
        assumeTrue("no targetPackage argument; nothing to verify", target.isNotBlank())
    }

    private fun client() = BridgeClient(context.contentResolver, target)

    private fun myUserId() = Process.myUid() / PER_USER_RANGE

    // ---- 1. the premise itself ---------------------------------------------

    @Test
    fun proxyIsReachableAcrossTheProcessBoundary() {
        // This single call proves a great deal at once: the proxy is installed for this user,
        // the signature-level BRIDGE permission was granted (so both APKs share a signing key),
        // the provider process started on demand, and the protocol matches.
        val info = client().ping(target)

        assertEquals(target, info.packageName)
        assertTrue(info.protocolCompatible, "protocol ${info.protocolVersion} is not ours")
        assertTrue(
            info.roots.any { it.root == StorageRoot.DATA && it.exists },
            "the proxy cannot see its own Android/data root: ${info.roots}",
        )
    }

    @Test
    fun proxyRunsInTheIntendedUserProfile() {
        val info = client().ping(target)

        // We must be in the same profile as the proxy, otherwise we are looking at a different
        // user's storage and every subsequent assertion is meaningless.
        assertEquals(
            myUserId(), info.userId,
            "test runs as user ${myUserId()} but the proxy is in user ${info.userId}",
        )
        if (expectedUser >= 0) {
            assertEquals(expectedUser, info.userId, "proxy is not in the requested profile")
        }
    }

    /**
     * The core assertion: bytes planted by `adb shell` into the secondary profile's private
     * storage are readable through the bridge.
     *
     * If this passes, the mechanism works. If it fails, nothing else in the project matters.
     */
    @Test
    fun plantedSaveDataIsReadableThroughTheBridge() {
        assumeTrue("CI did not plant fixtures", expectPlanted)

        val entries = client().list(StorageRoot.DATA, PLANTED_DIR)
        val names = entries.map { it.name }
        assertTrue(
            PLANTED_FILE in names,
            "planted file not visible through the bridge; saw $names — the FUSE grant may not apply",
        )

        val entry = entries.first { it.name == PLANTED_FILE }
        assertFalse(entry.isDirectory)
        assertEquals(PLANTED_CONTENTS.length.toLong(), entry.sizeBytes, "size disagrees")

        val read = client().openFile(StorageRoot.DATA, "$PLANTED_DIR/$PLANTED_FILE", "r").use { pfd ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }
        }
        assertEquals(PLANTED_CONTENTS, String(read, Charsets.UTF_8), "content disagrees")
    }

    @Test
    fun plantedObbIsReadableThroughTheBridge() {
        assumeTrue("CI did not plant fixtures", expectPlanted)

        val entries = client().list(StorageRoot.OBB)
        val obb = entries.firstOrNull { it.name == PLANTED_OBB }
        assertNotNull(obb, "planted .obb not visible; saw ${entries.map { it.name }}")
        assertTrue(obb.sizeBytes > 0)

        val read = client().openFile(StorageRoot.OBB, PLANTED_OBB, "r").use { pfd ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }
        }
        assertEquals(PLANTED_CONTENTS, String(read, Charsets.UTF_8))
    }

    // ---- 2. writes reach the real filesystem -------------------------------

    @Test
    fun writingThroughTheBridgeLandsOnTheRealFilesystem() {
        val path = "$PLANTED_DIR/written-by-test.txt"
        val payload = "written from user ${myUserId()} at ${System.currentTimeMillis()}"

        client().openFile(StorageRoot.DATA, path, "w").use { pfd ->
            android.os.ParcelFileDescriptor.AutoCloseOutputStream(pfd).use {
                it.write(payload.toByteArray())
            }
        }

        // Read it back through the bridge...
        val viaBridge = client().openFile(StorageRoot.DATA, path, "r").use { pfd ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }
        }
        assertEquals(payload, String(viaBridge))

        // ...and confirm it is a real file at the path the platform documents, not something
        // the provider invented. This is what makes the round trip meaningful.
        val expected = File("/storage/emulated/${myUserId()}/Android/data/$target/$path")
        assertTrue(expected.isFile, "expected a real file at ${expected.absolutePath}")
        assertEquals(payload, expected.readText())
    }

    // ---- 3. the restriction we are working around is real -------------------

    /**
     * Confirms the *problem* still exists, so the test suite cannot pass vacuously.
     *
     * If a future Android release opened `Android/data` back up, this would fail and tell us the
     * whole app had become unnecessary — which is exactly the signal we would want.
     */
    @Test
    fun thePlatformStillHidesOtherPackagesPrivateStorageFromUs() {
        val foreign = File("/storage/emulated/${myUserId()}/Android/data/$target")

        val listed = foreign.listFiles()
        val blocked = listed == null || listed.isEmpty()

        assertTrue(
            blocked,
            "we could read $foreign directly (${listed?.size} entries) — scoped storage is not " +
                "restricting us, so this app's premise no longer holds on this build",
        )

        // And our own directory is still reachable, proving the failure above is a permission
        // boundary rather than a missing mount.
        val own = context.getExternalFilesDir(null)
        assertNotNull(own, "our own external files dir should exist")
        assertTrue(own.isDirectory)
    }

    // ---- 4. safety rails hold on a real device ------------------------------

    @Test
    fun traversalIsRejectedByTheProviderOnDevice() {
        // Client-side validation should catch these first; the assertion is that they are caught
        // somewhere, and that the provider's own copy of the rule agrees.
        for (path in listOf("../etc/passwd", "saves/../../etc", "/absolute")) {
            val error = runCatching { client().list(StorageRoot.DATA, path) }.exceptionOrNull()
            assertNotNull(error, "traversal '$path' was not rejected")
            assertTrue(
                error is BridgeError.InvalidPath || error is BridgeError.RejectedByProxy ||
                    error is SecurityException || error is IllegalArgumentException,
                "unexpected rejection type for '$path': $error",
            )
        }
    }

    @Test
    fun aForgedAuthorityCannotBeUsedToReadAnotherPackage() {
        // The bridge must only ever serve the package it is installed as. Pointing a client at
        // some other authority should fail rather than quietly returning someone else's files.
        val forged = BridgeClient(context.contentResolver, "com.android.shell")
        val result = runCatching { forged.ping("com.android.shell") }
        assertTrue(result.isFailure, "a client pointed at an unrelated package should not succeed")
    }

    companion object {
        private const val PER_USER_RANGE = 100_000
        const val PLANTED_DIR = "planted"
        const val PLANTED_FILE = "save.dat"
        const val PLANTED_OBB = "main.1.com.example.planted.obb"
        const val PLANTED_CONTENTS = "understudy-fuse-premise-check"
    }
}
