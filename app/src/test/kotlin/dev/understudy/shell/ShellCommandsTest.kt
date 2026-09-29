package dev.understudy.shell

import dev.understudy.core.model.ProxyTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for the generated shell command text.
 *
 * These matter more than they look. The rename-aside sequence is the only path that resolves a
 * device-wide signature conflict without destroying the data it exists to protect, and a subtly
 * wrong `mv` — an unguarded one, a wrong user id, a missing quote — silently deletes someone's
 * save files. The commands are pure string construction, so they are fully testable on the JVM,
 * and that is exactly where they should be pinned down.
 */
class ShellCommandsTest {

    private val pkg = "com.example.targetgame"
    private val user = 10

    @Test
    fun `paths use the requested user id, never the owner's`() {
        assertEquals("/storage/emulated/10/Android/data/$pkg", ShellCommands.dataDir(user, pkg))
        assertEquals("/storage/emulated/10/Android/obb/$pkg", ShellCommands.obbDir(user, pkg))

        // The raw lower filesystem is what root must use: uid 0 is DENIED the FUSE view of
        // another user (observed on API 34/35 emulators), so ownership repair targets these.
        assertEquals("/data/media/10/Android/data/$pkg", ShellCommands.rawDataDir(user, pkg))
        assertEquals("/data/media/10/Android/obb/$pkg", ShellCommands.rawObbDir(user, pkg))

        // The whole point of the app is secondary profiles: a hardcoded /sdcard or emulated/0
        // here would operate on the wrong data and look like it succeeded.
        assertFalse(ShellCommands.dataDir(user, pkg).contains("emulated/0"))
        assertTrue(ShellCommands.dataDir(0, pkg).contains("emulated/0"))
        assertTrue(ShellCommands.dataDir(11, pkg).contains("emulated/11"))
        assertTrue(ShellCommands.rawDataDir(11, pkg).contains("/data/media/11/"))
    }

    @Test
    fun `rename-aside is guarded so re-running cannot destroy an existing backup`() {
        val script = ShellCommands.renameAside(user, pkg)

        // Every move must be conditional on the source existing AND the destination being free.
        // Without the `-e` guard, a second run would overwrite the first backup with an empty
        // directory created by the failed install, losing the data.
        assertTrue("[ -d " in script, "no directory test before moving")
        assertTrue("[ ! -e " in script, "no free-destination test before moving")
        assertTrue("|| true" in script, "a missing directory should not abort the sequence")
        assertTrue("set -e" in script, "the sequence must stop on a real failure")

        assertTrue(script.contains("mv '/storage/emulated/$user/Android/data/$pkg'"))
        assertTrue(ShellCommands.BACKUP_SUFFIX in script)
    }

    @Test
    fun `rename-back refuses to clobber a directory that reappeared`() {
        val script = ShellCommands.renameBack(user, pkg)
        assertTrue("[ ! -e " in script, "must not overwrite a directory that came back")
        assertTrue("[ -d " in script)
        val suffix = ShellCommands.BACKUP_SUFFIX
        assertTrue(script.contains("mv '/storage/emulated/$user/Android/data/$pkg$suffix'"), script)
    }

    @Test
    fun `the destructive uninstall is labelled and separated from the keeping one`() {
        val destroy = ShellCommands.uninstallEverywhere(pkg)
        val keep = ShellCommands.uninstallKeepingData(user, pkg)

        assertTrue(destroy.contains("pm uninstall $pkg"))
        assertFalse(destroy.contains("-k"), "the destructive form must not carry -k")
        assertTrue(destroy.contains("ONLY run this after the rename-aside step"))

        assertTrue(keep.contains("pm uninstall -k --user $user $pkg"))
        // --user must be present: without it, `pm uninstall -k` would act on the calling user.
        assertTrue(keep.contains("--user"))
    }

    @Test
    fun `install targets the requested user explicitly`() {
        val cmd = ShellCommands.installForUser(user, "/data/local/tmp/proxy.apk")
        assertTrue(cmd.contains("adb install --user $user"))
        assertTrue(cmd.contains("-r"), "reinstall flag expected so a retry does not need a cleanup")
    }

    @Test
    fun `the runbook orders the steps so data is moved before it can be deleted`() {
        val script = ShellCommands.fullRenameAsideRunbook(user, pkg)

        val inspect = script.indexOf("pm list packages")
        val aside = script.indexOf("mv '/storage/emulated/$user/Android/data/$pkg'")
        val destroy = script.indexOf("pm uninstall $pkg")
        val install = script.indexOf("adb install --user $user")
        val keepUninstall = script.indexOf("pm uninstall -k --user $user")
        val back = script.indexOf("mv '/storage/emulated/$user/Android/data/$pkg${ShellCommands.BACKUP_SUFFIX}'")

        assertTrue(inspect >= 0 && aside >= 0 && destroy >= 0 && install >= 0 &&
            keepUninstall >= 0 && back >= 0, "runbook is missing a step:\n$script")

        assertTrue(aside < destroy, "data must be moved aside BEFORE the destructive uninstall")
        assertTrue(destroy < install, "the name must be free before installing the proxy")
        assertTrue(install < keepUninstall, "install before teardown")
        assertTrue(keepUninstall < back, "restore only after the proxy is gone")
    }

    @Test
    fun `runbook explains why the conflict happens at all`() {
        val script = ShellCommands.fullRenameAsideRunbook(user, pkg)
        assertTrue("DEVICE-WIDE" in script || "device-wide" in script.lowercase())
        assertTrue("INSTALL_FAILED_UPDATE_INCOMPATIBLE" in script)
        // The `-k` trap is the part users get wrong most often.
        assertTrue("signature record" in script)
    }

    @Test
    fun `paths containing spaces or shell metacharacters stay quoted`() {
        // Package names cannot contain spaces, but the APK path can (a user-chosen Downloads
        // folder). Every interpolated path must be single-quoted.
        val push = ShellCommands.pushApk("/sdcard/My Games/proxy.apk")
        assertTrue(push.contains("'/sdcard/My Games/proxy.apk'"), push)

        val install = ShellCommands.installForUser(user, "/data/local/tmp/my proxy.apk")
        assertTrue(install.contains("'/data/local/tmp/my proxy.apk'"), install)
    }

    @Test
    fun `ownership repair targets the per-user uid on the raw filesystem`() {
        val script = ShellCommands.restoreOwnership(user, pkg)
        // Assertions about *executable* lines only — the block also carries explanatory
        // comments that legitimately name the wrong approaches.
        val code = script.lineSequence().filterNot { it.trimStart().startsWith("#") }
            .joinToString("\n")

        // The uid must be looked up at run time, never hardcoded: it differs per device and per
        // install, and a wrong chown is worse than none. The source must be
        // `pm list packages -U --user N`, which prints the PER-USER uid in a stable format —
        // NOT dumpsys, whose field was renamed from `userId=` to `appId=` in Android 14 (CI run
        // #11 died on exactly that grep) and whose value is the shared appId either way.
        assertTrue("pm list packages -U -u --user $user $pkg" in code, script)
        assertFalse("dumpsys" in code, "dumpsys field names vary by API level: $code")
        assertFalse("userId=" in code, code)

        // chown must be owner-only (`uid:uid` would clobber the ext_data_rw/ext_obb_rw group
        // the platform relies on) and hit the RAW paths — root is denied the FUSE view of
        // another user, and uid 2000 cannot chown at all.
        assertTrue("chown -R \$APP_UID '/data/media/$user/Android/data/$pkg'" in code, script)
        assertTrue("chown -R \$APP_UID '/data/media/$user/Android/obb/$pkg'" in code, script)
        assertFalse("\$APP_UID:\$APP_UID" in code, "must not clobber the group: $code")

        // chmod 771 clears the setgid bit, and the platform's mode is 2770 — never chmod here.
        assertFalse("chmod" in code, code)

        // bash's UID variable is READ-ONLY; `UID=$(...)` aborts the operator's shell script.
        assertFalse(Regex("(?m)^UID=").containsMatchIn(code), script)

        // An unresolvable uid must fail loudly instead of chowning to an empty string...
        assertTrue("[ -n \"\$APP_UID\" ]" in code, code)
        // ...and a failing chown must not abort a longer runbook.
        assertTrue("|| true" in code)
    }

    @Test
    fun `the runbook repairs ownership after restoring the directories`() {
        val script = ShellCommands.fullRenameAsideRunbook(user, pkg)
        val restore = script.indexOf("mv '/storage/emulated/$user/Android/data/$pkg${ShellCommands.BACKUP_SUFFIX}'")
        val chown = script.indexOf("chown -R")
        assertTrue(restore >= 0 && chown >= 0, "runbook is missing a step")
        assertTrue(restore < chown, "ownership must be repaired AFTER the directories are back")
    }

    @Test
    fun `manual backend identifies itself`() {
        val backend = ManualShellBackend()
        assertEquals("manual", backend.id)
        assertEquals("Copy-paste adb commands", backend.displayName)
        // Its execute() throws ShellUnavailable rather than faking a result; the orchestrator
        // uses that distinction to fall back to *showing* the commands instead of reporting a
        // failure. Exercising it needs a coroutine scope, so it is covered by the orchestrator
        // tests rather than here.
    }

    @Test
    fun `target model carries the user id through unchanged`() {
        val target = ProxyTarget(packageName = pkg, userId = user)
        assertEquals(user, target.userId)
        assertEquals(pkg, target.packageName)
        assertTrue(ShellCommands.uninstallKeepingData(target.userId, target.packageName).contains("--user $user"))
    }
}
