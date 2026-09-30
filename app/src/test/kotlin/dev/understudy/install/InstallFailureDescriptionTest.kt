package dev.understudy.install

import android.content.pm.PackageInstaller
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContains
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins [InstallResultReceiver.describe], the only explanation a user ever sees when a proxy
 * install fails.
 *
 * This had no coverage at all, which is a strange gap given how much of the project's design is
 * about failure modes the user cannot diagnose on their own. A raw
 * `INSTALL_FAILED_UPDATE_INCOMPATIBLE` tells nobody that package identity is device-wide and that
 * installing into a fresh profile therefore does not help; the whole rename-aside flow exists
 * because of that one misunderstanding, and `describe` is where the app says so.
 *
 * These are pure string functions, so they are cheap to pin — and worth pinning, because the
 * failure they describe is the one that arrives as a support request with no logcat attached.
 *
 * Robolectric is used only so the framework constants resolve; nothing here touches a device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class InstallFailureDescriptionTest {

    // ---- the failure that reached CI before it reached a user ----------------

    /**
     * Run #28 died on this in under ten seconds, before a single test executed:
     *
     * ```
     * INSTALL_FAILED_DUPLICATE_PERMISSION: Package com.example.prodgame attempting to redeclare
     *   permission dev.understudy.permission.BRIDGE already owned by com.example.targetgame
     * ```
     *
     * A custom permission may be defined by only one package on the device, and definitions are
     * device-wide like package identity is — so a second proxy could never be installed while an
     * older one that declared the permission itself was still present. The product fix was to
     * move the definition to `:app`; this asserts the *message* explains it, because a user who
     * upgrades with an old proxy still installed hits exactly this and has no other way to learn
     * what to do.
     */
    @Test
    fun duplicatePermissionNamesTheCollidingPermissionAndTheRemedy() {
        val raw = "INSTALL_FAILED_DUPLICATE_PERMISSION: Package com.example.prodgame attempting " +
            "to redeclare permission dev.understudy.permission.BRIDGE already owned by " +
            "com.example.targetgame"

        val out = InstallResultReceiver.describe(PackageInstaller.STATUS_FAILURE_CONFLICT, raw)

        assertContains(out, InstallResultReceiver.BRIDGE_PERMISSION)
        assertContains(out, "device-wide", message = "the user needs to know a different profile will not help")
        assertContains(out, "Uninstall", message = "there must be an actionable remedy, not just a diagnosis")
        assertContains(out, "INSTALL_FAILED_DUPLICATE_PERMISSION", message = "the raw code must survive for a bug report")
    }

    @Test
    fun duplicatePermissionIsRecognisedWhateverStatusCodeCarriesIt() {
        // The platform reports this under a generic status and puts the real reason in the
        // message, so matching on the status code alone would miss it.
        for (status in listOf(
            PackageInstaller.STATUS_FAILURE,
            PackageInstaller.STATUS_FAILURE_INVALID,
            PackageInstaller.STATUS_FAILURE_CONFLICT,
        )) {
            val out = InstallResultReceiver.describe(status, "INSTALL_FAILED_DUPLICATE_PERMISSION")
            assertContains(out, InstallResultReceiver.BRIDGE_PERMISSION, message = "status $status was not mapped")
        }
    }

    // ---- the other dominant failure ------------------------------------------

    @Test
    fun updateIncompatibleExplainsThatAnotherProfileDoesNotHelp() {
        val out = InstallResultReceiver.describe(
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE,
            "INSTALL_FAILED_UPDATE_INCOMPATIBLE: Package com.example.targetgame signatures do not " +
                "match; version 1, deleting retained data for user 10?",
        )

        assertContains(out, "device-wide")
        assertContains(out, "rename-aside")
        assertContains(out, "INSTALL_FAILED_UPDATE_INCOMPATIBLE")
    }

    @Test
    fun updateIncompatibleIsRecognisedFromTheStatusCodeAlone() {
        // No message at all — some OEM installers omit it. The status code must still route to
        // the explanation rather than printing "status=-106".
        val out = InstallResultReceiver.describe(
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE,
            null,
        )
        assertContains(out, "device-wide")
        assertContains(out, "rename-aside")
    }

    // ---- the benign one ------------------------------------------------------

    @Test
    fun userRejectionIsNotPhrasedAsAnError() {
        val out = InstallResultReceiver.describe(
            PackageInstaller.STATUS_FAILURE_ABORTED,
            "INSTALL_FAILED_USER_REJECTED",
        )
        assertContains(out, "declined")
        assertContains(out, "Nothing was installed")
        // It must not be dressed up as a device problem.
        assertTrue(
            !out.contains("device-wide") && !out.contains("signature"),
            "a declined prompt was described as an install conflict: $out",
        )
    }

    // ---- the rest of the mapping ---------------------------------------------

    @Test
    fun alreadyExistsSaysItIsAboutThisUser() {
        // Unlike the two device-wide failures, this one genuinely is per-user, and the message
        // has to say so or the user will go looking for a signature problem that is not there.
        val out = InstallResultReceiver.describe(
            PackageInstaller.STATUS_FAILURE_CONFLICT,
            "INSTALL_FAILED_ALREADY_EXISTS",
        )
        assertContains(out, "already installed for this user")
    }

    @Test
    fun insufficientStorageIsPlain() {
        val out = InstallResultReceiver.describe(
            PackageInstaller.STATUS_FAILURE_STORAGE,
            "INSTALL_FAILED_INSUFFICIENT_STORAGE",
        )
        assertContains(out, "storage")
    }

    @Test
    fun invalidApkAsksForTheInformationAReportNeeds() {
        val out = InstallResultReceiver.describe(
            PackageInstaller.STATUS_FAILURE_INVALID,
            "INSTALL_FAILED_INVALID_APK",
        )
        assertContains(out, "target package name")
    }

    // ---- fallthrough ---------------------------------------------------------

    @Test
    fun anUnmappedFailureKeepsTheRawText() {
        // Never swallow the platform's own words: an unmapped code that printed only a friendly
        // sentence would be unreportable.
        val raw = "INSTALL_FAILED_MISSING_SPLIT: some new failure we have not seen"
        val out = InstallResultReceiver.describe(PackageInstaller.STATUS_FAILURE_INVALID, raw)
        assertEquals(raw, out)
    }

    @Test
    fun aBlankMessageFallsBackToAReadableStatusName() {
        assertEquals(
            "STATUS_FAILURE_INCOMPATIBLE",
            InstallResultReceiver.describe(PackageInstaller.STATUS_FAILURE_INCOMPATIBLE, "")
                .substringBefore(" —"),
        )
        // A null message must not produce "status=-106" for a code we do have a name for.
        assertContains(
            InstallResultReceiver.describe(PackageInstaller.STATUS_FAILURE_STORAGE, null),
            "STATUS_FAILURE_STORAGE",
        )
    }

    @Test
    fun everyNamedStatusHasAName() {
        val statuses = mapOf(
            PackageInstaller.STATUS_FAILURE to "STATUS_FAILURE",
            PackageInstaller.STATUS_FAILURE_BLOCKED to "STATUS_FAILURE_BLOCKED",
            PackageInstaller.STATUS_FAILURE_ABORTED to "STATUS_FAILURE_ABORTED",
            PackageInstaller.STATUS_FAILURE_INVALID to "STATUS_FAILURE_INVALID",
            PackageInstaller.STATUS_FAILURE_CONFLICT to "STATUS_FAILURE_CONFLICT",
            PackageInstaller.STATUS_FAILURE_STORAGE to "STATUS_FAILURE_STORAGE",
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE to "STATUS_FAILURE_INCOMPATIBLE",
        )
        for ((status, name) in statuses) {
            val out = InstallResultReceiver.describe(status, null)
            assertContains(out, name, message = "status $status did not render as $name")
            assertTrue(!out.startsWith("status="), "status $status fell through to the numeric form")
        }
    }

    @Test
    fun thePermissionNameInThisClassIsTheOneTheManifestsUse() {
        // describe() has to name the permission the user must go and remove. If it drifted from
        // the one actually declared, the advice would point at a permission that does not exist.
        assertEquals(
            "dev.understudy.permission.BRIDGE",
            InstallResultReceiver.BRIDGE_PERMISSION,
        )
    }
}
