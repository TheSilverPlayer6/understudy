package dev.understudy.core

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import dev.understudy.bridge.BridgeContract
import dev.understudy.core.model.ProxyTarget
import dev.understudy.core.model.StorageRoot
import dev.understudy.install.ApkGenerator
import dev.understudy.install.InstallResultReceiver
import dev.understudy.install.ProxyInstaller
import dev.understudy.proxytpl.bridge.ProxyFileBridge
import dev.understudy.shell.ShellBackend
import dev.understudy.shell.ShellResult
import dev.understudy.shell.ShellUnavailable
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The transition guards of the session state machine — the safety-critical tests in the app.
 *
 * Every other failure mode in Understudy is recoverable: a bad APK can be regenerated, a failed
 * install retried. Except one. Tearing down before the data is accounted for destroys the only
 * copy of someone's save file, so most of the assertions below are about *refusals*: what the
 * manager will not do, what it says about the data when it stops, and — proved through
 * Robolectric's `ShadowPackageInstaller`, not hoped for — that the system uninstall was never
 * even requested.
 *
 * Fidelity choices, in the project's "test against real artifacts" idiom:
 *  - the bridge paths (verify, keep-hidden, evacuate-wipe) run against the **real**
 *    [ProxyFileBridge] registered as a real provider, exactly as [dev.understudy.bridge.BridgeIntegrationTest]
 *    does; a [MisbehavingBridge] stands in only where the test needs a *sick* proxy, which the
 *    healthy one cannot simulate;
 *  - the installer is the **real** [ProxyInstaller] over Robolectric's PackageInstaller shadow,
 *    so session staging, abandoning and uninstall requests are real framework interactions;
 *  - APK generation is the **real** [ApkGenerator] over the committed template asset, so the
 *    invalid-package-name refusal is the real `ManifestPatcher` validation, not a stub's idea
 *    of it;
 *  - only [ShellBackend] is faked — it is an interface designed for it, and the commands it
 *    receives are the real [dev.understudy.shell.ShellCommands] output.
 *
 * What this suite still cannot prove is the kernel: FUSE behaviour lives in the CI emulator
 * job, not here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@OptIn(ExperimentalCoroutinesApi::class)
class SessionManagerTest {

    private lateinit var app: Context
    private lateinit var fakeShell: FakeShell
    private lateinit var dataRoot: File
    private lateinit var obbRoot: File

    /**
     * The package the proxy impersonates. It must equal the Robolectric application's package:
     * the real [ProxyFileBridge] answers `ping` with `ctx.packageName`, and [dev.understudy.bridge.BridgeClient]
     * treats any other answer as [dev.understudy.bridge.BridgeError.WrongIdentity]. That is the
     * same self-consistency the production proxy has — its manifest package IS the host package.
     */
    private lateinit var pkg: String
    private lateinit var target: ProxyTarget

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        pkg = app.packageName
        target = ProxyTarget(packageName = pkg, userId = 0)
        fakeShell = FakeShell()
        shadowOf(app.packageManager).setCanRequestPackageInstalls(true)

        // The app-specific external tree, derived EXACTLY the way ProxyFileBridge derives it
        // (and the way BridgeIntegrationTest does): getExternalFilesDir(null).parentFile
        // .parentFile is what the provider treats as Android/data/<pkg>, with the obb root as
        // its sibling. Robolectric's on-disk layout is emulated rather than a device's, so the
        // package name is not a path segment here — what matters is that the test and the
        // provider resolve to the SAME directories, or wipe/keep-hidden assertions would
        // silently look at different trees.
        val filesDir = app.getExternalFilesDir(null)
        assertNotNull(filesDir, "external files dir unavailable")
        dataRoot = filesDir.parentFile!!.parentFile!!
        assertTrue(dataRoot.isDirectory, "expected $dataRoot to be the provider's data root")
        obbRoot = File(dataRoot.parentFile, "obb/$pkg")
        assertTrue(obbRoot.mkdirs() || obbRoot.isDirectory, "could not create $obbRoot")
    }

    // ---- the guards that protect data --------------------------------------

    @Test
    fun `destroying data without explicit confirmation is refused`() = runTest {
        val m = ready()

        val ok = m.teardown(target, TeardownStrategy.DESTROY_DATA)

        assertFalse(ok, "teardown must not report success")
        val failed = assertIs<SessionState.Failed>(m.state.value)
        assertFalse(failed.dataAtRisk, "a pure refusal is not a data risk: $failed")
        assertTrue(
            "confirmation" in failed.reason.lowercase(),
            "the refusal should name the missing confirmation: ${failed.reason}",
        )
        // The point of the guard: the system was never even asked to uninstall.
        assertNull(packageInstallerShadow().getLastUninstalledStatusReceiver(pkg))
        assertEquals(emptyList(), fakeShell.executed, "no shell command should have run")
    }

    @Test
    fun `destroying data with confirmation proceeds and never claims the data survived`() =
        runTest {
            val m = ready()

            val ok = m.teardown(target, TeardownStrategy.DESTROY_DATA, confirmDataLoss = true)

            assertTrue(ok)
            val finished = assertIs<SessionState.Finished>(m.state.value)
            assertFalse(
                finished.dataPreserved,
                "DESTROY_DATA must never report the data as preserved — the UI keys its warning off this",
            )
            assertNotNull(
                packageInstallerShadow().getLastUninstalledStatusReceiver(pkg),
                "the system uninstall should have been requested",
            )
            // The user is told about the "Keep app data" checkbox: at this point it is the only
            // unprivileged escape hatch left.
            assertIs<SessionEvent.UninstallOfferedKeepData>(m.events.value)
            assertNull(m.bridge(), "the proxy is being uninstalled; the bridge must not be reused")
        }

    @Test
    fun `evacuate-then-uninstall is refused until a pull has verifiably completed`() = runTest {
        val m = ready()

        val ok = m.teardown(target, TeardownStrategy.EVACUATE_THEN_UNINSTALL)

        assertFalse(ok)
        val failed = assertIs<SessionState.Failed>(m.state.value)
        assertFalse(failed.dataAtRisk, "nothing happened to the data; the manager only declined")
        assertTrue("no completed pull" in failed.reason, failed.reason)
        assertNull(packageInstallerShadow().getLastUninstalledStatusReceiver(pkg))
    }

    @Test
    fun `evacuate-then-uninstall wipes through the real proxy before any uninstall`() = runTest {
        val m = ready()
        val precious = File(dataRoot, "save.dat").apply { writeText("irreplaceable") }
        assertTrue(precious.isFile)
        m.recordVerifiedPull(target)

        val ok = m.teardown(target, TeardownStrategy.EVACUATE_THEN_UNINSTALL)

        assertTrue(ok)
        assertIs<SessionState.Finished>(m.state.value)
        // Order is the whole safety argument: the directories must be EMPTY before the system
        // is allowed to uninstall, because that uninstall deletes whatever is still in them.
        assertFalse(precious.exists(), "the proxy should have wiped its own data first")
        assertNotNull(packageInstallerShadow().getLastUninstalledStatusReceiver(pkg))
    }

    @Test
    fun `a wipe the proxy cannot perform aborts teardown with the data flagged at risk`() =
        runTest {
            val m = ready(misbehaving = true)
            m.recordVerifiedPull(target)

            val ok = m.teardown(target, TeardownStrategy.EVACUATE_THEN_UNINSTALL)

            assertFalse(ok, "a failed wipe must not lead to an uninstall")
            val failed = assertIs<SessionState.Failed>(m.state.value)
            assertTrue(
                failed.dataAtRisk,
                "we could not confirm the directories were empty, so the data IS ambiguous",
            )
            assertNull(packageInstallerShadow().getLastUninstalledStatusReceiver(pkg))
        }

    @Test
    fun `evacuate is refused when the bridge died after the pull was recorded`() = runTest {
        // The process-death shape of the same hazard: a pull completed earlier, but the proxy
        // is no longer reachable, so nothing can confirm the directories are empty now.
        val m = manager()
        m.recordVerifiedPull(target)
        assertNull(m.bridge(), "no verify ran, so there is no live bridge")

        val ok = m.teardown(target, TeardownStrategy.EVACUATE_THEN_UNINSTALL)

        assertFalse(ok)
        val failed = assertIs<SessionState.Failed>(m.state.value)
        assertTrue(
            failed.dataAtRisk,
            "a null bridge is not a successful wipe; the data state is unknown",
        )
        assertNull(packageInstallerShadow().getLastUninstalledStatusReceiver(pkg))
    }

    @Test
    fun `a failing shell teardown flags the data as at risk`() = runTest {
        fakeShell.result = ShellResult(exitCode = 1, output = "Failure [DELETE_FAILED_INTERNAL_ERROR]")
        val m = ready()

        val ok = m.teardown(target, TeardownStrategy.SHELL_KEEP_DATA)

        assertFalse(ok)
        val failed = assertIs<SessionState.Failed>(m.state.value)
        assertTrue(
            failed.dataAtRisk,
            "the uninstall may have partially run; the state of the data is unknown",
        )
        assertTrue("exit 1" in failed.reason, failed.reason)
    }

    @Test
    fun `a shell that only generates commands surfaces them instead of failing silently`() =
        runTest {
            fakeShell.unavailable = true
            val m = ready()

            val ok = m.teardown(target, TeardownStrategy.SHELL_KEEP_DATA)

            assertFalse(ok)
            // The user is not left stranded: the exact command is handed over.
            val event = assertIs<SessionEvent.ShowCommands>(m.events.value)
            assertTrue("pm uninstall -k --user 0 $pkg" in event.commands, event.commands)
            val failed = assertIs<SessionState.Failed>(m.state.value)
            assertFalse(failed.dataAtRisk, "nothing ran, so nothing is at risk")
        }

    @Test
    fun `shell keep-data teardown reports the data as preserved and drops the bridge`() =
        runTest {
            val m = ready()
            val precious = File(dataRoot, "save.dat").apply { writeText("irreplaceable") }

            val ok = m.teardown(target, TeardownStrategy.SHELL_KEEP_DATA)

            assertTrue(ok)
            val finished = assertIs<SessionState.Finished>(m.state.value)
            assertTrue(finished.dataPreserved, "`pm uninstall -k` keeps the directories")
            assertEquals(1, fakeShell.executed.size)
            assertTrue(
                "pm uninstall -k --user 0 $pkg" in fakeShell.executed.single(),
                fakeShell.executed.single(),
            )
            assertTrue(precious.isFile, "shell teardown must not touch the data itself")
            assertNull(m.bridge(), "the proxy is uninstalled; a stale client must not be reusable")
        }

    @Test
    fun `shell keep-data without a configured backend fails instead of pretending`() = runTest {
        val m = ready(shell = null)

        val ok = m.teardown(target, TeardownStrategy.SHELL_KEEP_DATA)

        assertFalse(ok)
        val failed = assertIs<SessionState.Failed>(m.state.value)
        assertFalse(failed.dataAtRisk)
        assertTrue("No shell backend" in failed.reason, failed.reason)
    }

    @Test
    fun `keep-hidden needs no privilege and leaves the data untouched`() = runTest {
        val m = ready(shell = null)
        val precious = File(dataRoot, "save.dat").apply { writeText("irreplaceable") }

        val ok = m.teardown(target, TeardownStrategy.KEEP_HIDDEN)

        assertTrue(ok)
        assertIs<SessionState.Dormant>(m.state.value)
        assertTrue(precious.isFile, "keep-hidden must not touch the data")
        assertNull(packageInstallerShadow().getLastUninstalledStatusReceiver(pkg))
        assertEquals(emptyList(), fakeShell.executed)
    }

    @Test
    fun `keep-hidden fails rather than pretending when the icon cannot be hidden`() = runTest {
        val m = ready(misbehaving = true)

        val ok = m.teardown(target, TeardownStrategy.KEEP_HIDDEN)

        assertFalse(ok)
        // Not a data risk — the data is fine; the proxy is merely still visible.
        val failed = assertIs<SessionState.Failed>(m.state.value)
        assertFalse(failed.dataAtRisk)
        assertTrue("hide" in failed.reason.lowercase(), failed.reason)
    }

    @Test
    fun `keep-hidden without a live bridge refuses instead of silently doing nothing`() =
        runTest {
            val m = manager()

            val ok = m.teardown(target, TeardownStrategy.KEEP_HIDDEN)

            assertFalse(ok)
            val failed = assertIs<SessionState.Failed>(m.state.value)
            assertFalse(failed.dataAtRisk)
        }

    // ---- strategy availability ----------------------------------------------

    @Test
    fun `the safest strategy is first and destroy is last`() = runTest {
        val m = ready()
        fakeShell.available = false

        val strategies = m.availableTeardownStrategies(target)

        assertEquals(TeardownStrategy.KEEP_HIDDEN, strategies.first())
        assertEquals(TeardownStrategy.DESTROY_DATA, strategies.last())
        assertFalse(
            TeardownStrategy.SHELL_KEEP_DATA in strategies,
            "a shell that is not available must not be offered",
        )
        assertFalse(
            TeardownStrategy.EVACUATE_THEN_UNINSTALL in strategies,
            "evacuate requires a recorded pull",
        )
    }

    @Test
    fun `strategies appear as their preconditions are met, safest first`() = runTest {
        val m = ready()
        fakeShell.available = true

        assertTrue(TeardownStrategy.SHELL_KEEP_DATA in m.availableTeardownStrategies(target))

        m.recordVerifiedPull(target)
        // The ordering is a promise the UI relies on when it preselects a default.
        assertEquals(
            listOf(
                TeardownStrategy.KEEP_HIDDEN,
                TeardownStrategy.SHELL_KEEP_DATA,
                TeardownStrategy.EVACUATE_THEN_UNINSTALL,
                TeardownStrategy.DESTROY_DATA,
            ),
            m.availableTeardownStrategies(target),
        )
    }

    @Test
    fun `a recorded pull for one target does not unlock evacuation of another`() = runTest {
        val m = ready()
        m.recordVerifiedPull(target)

        val other = ProxyTarget(packageName = "com.other.game", userId = 0)
        assertFalse(
            TeardownStrategy.EVACUATE_THEN_UNINSTALL in m.availableTeardownStrategies(other),
            "the pull proved nothing about a different package's data",
        )
    }

    @Test
    fun `a shell backend that throws while being probed counts as unavailable`() = runTest {
        fakeShell.probeThrows = true
        val m = ready()

        val strategies = m.availableTeardownStrategies(target)

        assertFalse(TeardownStrategy.SHELL_KEEP_DATA in strategies)
        assertTrue(TeardownStrategy.KEEP_HIDDEN in strategies, "the no-privilege path always survives")
    }

    // ---- generate ------------------------------------------------------------

    @Test
    fun `a successful generate settles back to Idle with the APK staged`() = runTest {
        val m = manager()

        m.generate(target)
        assertIs<SessionState.Generating>(m.state.value, "generation should be in flight")
        advanceUntilIdle()

        // Staying in Generating here would wedge the UI on its progress row forever and the
        // re-entrance guard would block generating for any other target.
        assertIs<SessionState.Idle>(m.state.value)
        val apk = assertNotNull(m.lastGenerated.value)
        assertEquals(pkg, apk.packageName)
        assertTrue(apk.file.isFile, "the real generator should have written a real file")
        assertIs<SessionEvent.ApkReady>(m.events.value)
    }

    @Test
    fun `generate refuses a package name the real patcher rejects`() = runTest {
        val m = manager()

        m.generate(ProxyTarget(packageName = "", userId = 0))
        advanceUntilIdle()

        val failed = assertIs<SessionState.Failed>(m.state.value)
        assertFalse(failed.dataAtRisk)
        assertEquals("Check the package name is valid.", failed.recoveryHint)
    }

    @Test
    fun `a second generate while one is in flight is ignored, not raced`() = runTest {
        val m = manager()

        m.generate(target)
        m.generate(ProxyTarget(packageName = "com.other.game", userId = 0))
        advanceUntilIdle()

        assertIs<SessionState.Idle>(m.state.value)
        assertEquals(
            pkg,
            assertNotNull(m.lastGenerated.value).packageName,
            "the guarded second call must not overwrite the first result",
        )
    }

    // ---- install preconditions ------------------------------------------------

    @Test
    fun `install before generate says so instead of installing nothing`() = runTest {
        val m = manager()

        val ok = m.install(target) { resultIntent() }

        assertFalse(ok)
        assertTrue("Generate" in assertIs<SessionEvent.Message>(m.events.value).text)
        assertIs<SessionState.Idle>(m.state.value, "a refused install must not change the state")
        assertTrue(liveSessions().isEmpty())
    }

    @Test
    fun `install without the per-profile grant routes the user to settings`() = runTest {
        shadowOf(app.packageManager).setCanRequestPackageInstalls(false)
        val m = stagedManager()

        val ok = m.install(target) { resultIntent() }

        assertFalse(ok)
        // REQUEST_INSTALL_PACKAGES is a PER-USER appop; the event carries the user so the UI can
        // say "grant it inside this profile" — the part users routinely get wrong.
        assertEquals(target.userId, assertIs<SessionEvent.NeedsInstallPermission>(m.events.value).userId)
        assertTrue(liveSessions().isEmpty(), "nothing should have been staged")
    }

    @Test
    fun `installing into another profile is refused with the adb equivalent`() = runTest {
        val m = stagedManager()
        val otherProfile = ProxyTarget(packageName = pkg, userId = 10)

        val ok = m.install(otherProfile) { resultIntent() }

        assertFalse(ok)
        val event = assertIs<SessionEvent.CrossUserUnsupported>(m.events.value)
        assertEquals(10, event.requested)
        assertEquals(0, event.current)
        assertNotNull(event.commands, "with a shell backend present the adb route should be offered")
        assertTrue("adb install --user 10" in event.commands!!, event.commands)
        assertTrue(
            liveSessions().isEmpty(),
            "PackageInstaller cannot cross users; a session must not even be created",
        )
    }

    @Test
    fun `installing an APK generated for a different package is refused`() = runTest {
        val m = stagedManager(forPackage = "com.other.game")

        val ok = m.install(target) { resultIntent() }

        assertFalse(ok)
        // The mistake a stale UI state makes: staged APK and requested target disagree, and
        // proceeding would put the WRONG package name into the profile.
        assertTrue("Generate" in assertIs<SessionEvent.Message>(m.events.value).text)
        assertTrue(liveSessions().isEmpty())
    }

    @Test
    fun `a successful install stages a real session and waits on the human`() = runTest {
        val m = stagedManager()

        val ok = m.install(target) { resultIntent() }

        assertTrue(ok)
        val installing = assertIs<SessionState.Installing>(m.state.value)
        assertTrue(
            installing.awaitingUser,
            "the common path is a confirmation prompt; the UI renders that very differently",
        )
        assertEquals(target, installing.target)
        assertEquals(1, liveSessions().size, "a real session should be staged")
    }

    @Test
    fun `a staging failure is reported and does not leave the session Installing`() = runTest {
        val m = stagedManager()
        // The realistic version of a staging failure: the cache was reclaimed between generate
        // and install.
        assertTrue(m.lastGenerated.value!!.file.delete(), "fixture: the APK file should delete")

        val ok = m.install(target) { resultIntent() }

        assertFalse(ok)
        val failed = assertIs<SessionState.Failed>(m.state.value)
        assertFalse(failed.dataAtRisk)
        assertTrue("Could not stage" in failed.reason, failed.reason)
    }

    // ---- install outcomes ------------------------------------------------------

    @Test
    fun `a success broadcast verifies the bridge and lands in Ready`() = runTest {
        registerProxy(misbehaving = false)
        val m = stagedManager()
        m.install(target) { resultIntent() }
        val sessionId = assertIs<SessionState.Installing>(m.state.value).sessionId

        InstallResultReceiver.listener!!.invoke(
            InstallResultReceiver.Outcome.Success(sessionId, pkg),
        )
        advanceUntilIdle()

        val ready = assertIs<SessionState.Ready>(m.state.value)
        assertEquals(0, ready.bridgeUserId)
        assertNotNull(m.bridge())
    }

    @Test
    fun `a pending-user-action broadcast keeps us Installing and hands the intent to the UI`() =
        runTest {
            val m = stagedManager()
            m.install(target) { resultIntent() }
            val confirmation = Intent(Intent.ACTION_VIEW)

            InstallResultReceiver.listener!!.invoke(
                InstallResultReceiver.Outcome.NeedsConfirmation(777, pkg, confirmation),
            )
            advanceUntilIdle()

            val installing = assertIs<SessionState.Installing>(m.state.value)
            assertTrue(installing.awaitingUser)
            assertEquals(777, installing.sessionId)
            assertIs<SessionEvent.LaunchIntent>(m.events.value)
        }

    @Test
    fun `an incompatible-signature failure points at the rename-aside runbook`() = runTest {
        val m = stagedManager()
        m.install(target) { resultIntent() }
        val sessionId = assertIs<SessionState.Installing>(m.state.value).sessionId

        InstallResultReceiver.listener!!.invoke(
            InstallResultReceiver.Outcome.Failure(
                sessionId = sessionId,
                packageName = pkg,
                status = PackageInstaller.STATUS_FAILURE_INCOMPATIBLE,
                reason = "INSTALL_FAILED_UPDATE_INCOMPATIBLE: signatures do not match",
            ),
        )
        advanceUntilIdle()

        val failed = assertIs<SessionState.Failed>(m.state.value)
        val hint = assertNotNull(failed.recoveryHint, "this failure has a specific remedy")
        assertTrue("rename-aside" in hint, hint)
        assertFalse(failed.dataAtRisk, "nothing was installed and nothing was touched")
        assertTrue(
            liveSessions().none { it.sessionId == sessionId },
            "the failed session must be abandoned, not left lingering in the system",
        )
    }

    @Test
    fun `a stray install broadcast outside the Installing state is ignored`() = runTest {
        val m = manager()

        // The process-death shape: a receiver outlives the state that started it. A late
        // broadcast must not resurrect the flow or fabricate a session.
        InstallResultReceiver.listener!!.invoke(
            InstallResultReceiver.Outcome.Success(999, pkg),
        )
        advanceUntilIdle()

        assertIs<SessionState.Idle>(m.state.value)
        assertNull(m.bridge())
    }

    // ---- verify -----------------------------------------------------------------

    @Test
    fun `verify reports which roots actually exist`() = runTest {
        registerProxy(misbehaving = false)
        val m = manager()

        m.verify(target)

        val ready = assertIs<SessionState.Ready>(m.state.value)
        assertEquals(0, ready.bridgeUserId)
        assertTrue(StorageRoot.DATA in ready.availableRoots, "the provider's own data root must exist")
        assertFalse(ready.hiddenFromLauncher, "a fresh proxy starts visible")
        assertNotNull(m.bridge())
    }

    @Test
    fun `a proxy in the wrong profile is a hard failure, not a warning`() = runTest {
        registerProxy(misbehaving = false)
        val m = manager()
        // The provider process is user 0 here; asking for user 10 is the "you are looking at the
        // wrong profile's storage" case — the scariest silent failure this app could have.
        val wrongProfile = ProxyTarget(packageName = pkg, userId = 10)

        m.verify(wrongProfile)

        val failed = assertIs<SessionState.Failed>(m.state.value)
        assertTrue("user 10" in failed.reason, failed.reason)
        assertNotNull(failed.recoveryHint)
        assertNull(m.bridge(), "a bridge in the wrong profile must not be handed to the UI")
    }

    @Test
    fun `an unreachable proxy explains the likely cause`() = runTest {
        val m = manager()
        val absent = ProxyTarget(packageName = "com.never.installed", userId = 0)

        m.verify(absent)

        val failed = assertIs<SessionState.Failed>(m.state.value)
        // What is guaranteed here is the safety part: nothing was handed to the UI and the
        // failure is not blamed on the data. The recovery hint is keyed to specific
        // BridgeError subtypes, and which one an unregistered authority produces is a
        // property of the platform (a real device throws, Robolectric returns null), so it
        // is deliberately not asserted.
        assertFalse(failed.dataAtRisk)
        assertNull(m.bridge(), "an unreachable proxy must not yield a bridge")
        assertTrue(
            failed.reason.isNotBlank(),
            "the user needs to be told something: ${failed.reason}",
        )
    }

    // ---- lifecycle hygiene -------------------------------------------------------

    @Test
    fun `reset clears everything a resumed session could otherwise reuse`() = runTest {
        val m = ready()
        m.recordVerifiedPull(target)

        m.reset()

        assertIs<SessionState.Idle>(m.state.value)
        assertNull(m.bridge())
        assertNull(m.lastGenerated.value)
        // The recorded pull is the precondition for a destructive teardown, so it must not
        // survive: after a reset the files on disk may have changed.
        assertFalse(
            TeardownStrategy.EVACUATE_THEN_UNINSTALL in m.availableTeardownStrategies(target),
            "a reset must not leave evacuate unlocked",
        )
    }

    // ---- fixtures -----------------------------------------------------------------

    /**
     * A manager whose coroutines run on *this* test's scheduler.
     *
     * `StandardTestDispatcher(testScheduler)` rather than a fresh dispatcher: coroutines-test
     * rejects mixing schedulers, and `advanceUntilIdle()` only drives tasks on the scheduler it
     * belongs to. Both the manager's scope and its IO hop must be on the test's scheduler, or
     * the install-broadcast launches — which arrive from outside the test coroutine — are still
     * queued when the assertions run.
     */
    private fun TestScope.manager(shell: ShellBackend? = fakeShell): SessionManager {
        val d = StandardTestDispatcher(testScheduler)
        return SessionManager(
            context = app,
            apkGenerator = ApkGenerator(app),
            installer = ProxyInstaller(app),
            shell = shell,
            scope = CoroutineScope(d),
            io = d,
        )
    }

    private fun TestScope.stagedManager(forPackage: String = pkg): SessionManager {
        val m = manager()
        m.generate(ProxyTarget(packageName = forPackage, userId = 0))
        advanceUntilIdle()
        assertNotNull(m.lastGenerated.value, "fixture: the APK should have been generated")
        assertIs<SessionState.Idle>(m.state.value, "fixture: generate should have settled")
        return m
    }

    private suspend fun TestScope.ready(
        shell: ShellBackend? = fakeShell,
        misbehaving: Boolean = false,
    ): SessionManager {
        registerProxy(misbehaving)
        val m = manager(shell)
        m.verify(target)
        assertIs<SessionState.Ready>(m.state.value, "fixture: should have reached Ready")
        return m
    }

    private fun registerProxy(misbehaving: Boolean) {
        val clazz = if (misbehaving) MisbehavingBridge::class.java else ProxyFileBridge::class.java
        Robolectric.buildContentProvider(clazz).create(pkg)
    }

    private fun packageInstallerShadow() =
        shadowOf(app.packageManager.packageInstaller)

    /**
     * Sessions the shadow still holds.
     *
     * Read through the real `PackageInstaller` rather than the shadow's own list because
     * `ShadowPackageInstaller.getAllSessions()` is protected: it is an `@Implementation` of
     * `getMySessions()`, not a test API. Going through the framework method asserts the same
     * fact and keeps the suite honest about which side of the boundary it is looking at.
     */
    private fun liveSessions(): List<PackageInstaller.SessionInfo> =
        app.packageManager.packageInstaller.mySessions

    private fun resultIntent(): android.app.PendingIntent =
        android.app.PendingIntent.getBroadcast(
            app,
            0,
            Intent(app, InstallResultReceiver::class.java),
            android.app.PendingIntent.FLAG_MUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private class FakeShell : ShellBackend {
        var available = true
        var unavailable = false
        var probeThrows = false
        var result = ShellResult(exitCode = 0, output = "Success")
        val executed = mutableListOf<String>()

        override val id = "fake"
        override val displayName = "Fake shell"

        override suspend fun isAvailable(): Boolean {
            if (probeThrows) throw RuntimeException("probe blew up")
            return available
        }

        override suspend fun execute(command: String): ShellResult {
            if (unavailable) throw ShellUnavailable("fake backend cannot execute")
            executed += command
            return result
        }
    }

}

/**
 * A proxy that answers `ping` correctly but fails every mutation.
 *
 * It exists because the healthy [ProxyFileBridge] cannot simulate a sick proxy, and "the proxy
 * stopped cooperating mid-teardown" is precisely the situation the data-risk flags are for.
 * The `ping` reply mirrors [ProxyFileBridge]'s contract exactly — protocol version, package,
 * user, roots — so [dev.understudy.bridge.BridgeClient] reaches Ready the same way it would on
 * a device.
 */
private class MisbehavingBridge : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String = "vnd.android.cursor.item/vnd.understudy.misbehaving"

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val out = Bundle()
        if (method == BridgeContract.CALL_PING) {
            val ctx = context!!
            out.putBoolean(BridgeContract.KEY_OK, true)
            out.putInt(BridgeContract.KEY_PROTOCOL, BridgeContract.PROTOCOL_VERSION)
            out.putString(BridgeContract.KEY_PACKAGE, ctx.packageName)
            out.putInt(BridgeContract.KEY_USER, Process.myUid() / 100_000)
            val roots = Bundle()
            for (name in BridgeContract.ROOTS) {
                roots.putBundle(
                    name,
                    Bundle().apply {
                        putBoolean("exists", true)
                        putString("path", "/misbehaving/$name")
                        putLong("usableBytes", 1024L)
                    },
                )
            }
            out.putBundle(BridgeContract.KEY_ROOTS, roots)
        } else {
            out.putBoolean(BridgeContract.KEY_OK, false)
            out.putString(BridgeContract.KEY_ERROR, "simulated proxy failure")
        }
        return out
    }
}
