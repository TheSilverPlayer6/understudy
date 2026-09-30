package dev.understudy.bridge

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pins the two failure messages that a user or a support request has to act on, and the mapping
 * that chooses between them.
 *
 * Both were **wrong** before milestone 5, in the specific way that is most expensive: confidently
 * naming a cause that could not be the one.
 *
 *  - `ProxyUnreachable` said "Is it installed in this user profile?". From API 30 an installed,
 *    running proxy that the app is not allowed to *see* produces the identical
 *    `IllegalArgumentException: Unknown authority`, so the answer was "yes, it is installed" and
 *    the user was sent to retry the install forever. CI run #29 is the evidence: five tests failing
 *    with that message while `adb shell content query` against the same URI returned rows in the
 *    same log, because the shell is not visibility-filtered and the app is.
 *  - `PermissionDenied` said the proxy "was probably signed with a different key". The proxy
 *    authenticates its caller against the **generator's** certificate, so the proxy's own key is
 *    irrelevant to that check; what changes is which build of Understudy is installed. Worse, the
 *    `signature`-level permission it blamed is defined by `:app`, so `:app` always holds it
 *    whatever signed the proxy — the message pointed at the one certificate that could not be the
 *    problem.
 *
 * Message tests are usually brittle and not worth writing. These are, because the strings are the
 * diagnosis: there is no logcat on a user's device and nobody can attach one. A test that fails
 * when the explanation drifts back into blaming the wrong component is cheaper than the support
 * thread.
 */
class BridgeErrorTest {

    private val authority = "com.example.targetgame"

    @Test
    fun unreachableNamesBothCausesAndGivesTheRemedyThatWorksForEither() {
        val e = BridgeError.ProxyUnreachable(authority, IllegalArgumentException("Unknown authority $authority"))
        val m = e.message!!

        assertContains(m, authority)
        // Cause 1: genuinely not installed.
        assertContains(m, "not installed")
        // Cause 2: installed but not visible. Omitting this is the bug that cost a CI run.
        assertContains(
            m, "not visible",
            message = "must name package visibility, or an installed proxy reads as a failed install",
        )
        // And the remedy, which is the same for the visibility cause and harmless for the other.
        assertContains(m, "Regenerate")
    }

    @Test
    fun unreachableDoesNotBlameTheProxysSigningKey() {
        val m = BridgeError.ProxyUnreachable(authority, RuntimeException()).message!!
        // A visibility problem has nothing to do with any certificate. Mentioning one here sends
        // the user to uninstall and regenerate for the wrong reason, or worse, to distrust the app.
        assertTrue(
            !m.contains("signed", ignoreCase = true) && !m.contains("key", ignoreCase = true),
            "an unreachable proxy was explained in terms of signing: $m",
        )
    }

    @Test
    fun deniedBlamesTheGeneratorRelationshipNotTheProxysKey() {
        val e = BridgeError.PermissionDenied(authority, SecurityException("caller uid 1010141 is not the Understudy install that generated this proxy"))
        val m = e.message!!

        assertContains(m, authority)
        assertContains(m, "not the Understudy install that generated it")
        assertContains(m, "Regenerate")
        // The proxy's own signing key is not what enforceCaller checks. Saying so was the old bug.
        assertTrue(
            !m.contains("proxy was signed", ignoreCase = true),
            "still blaming the proxy's signing key: $m",
        )
    }

    @Test
    fun deniedExplainsWhatInvalidatesTheRelationship() {
        // The user has to understand why this happened to them, or the remedy looks arbitrary.
        val m = BridgeError.PermissionDenied(authority, SecurityException()).message!!
        assertContains(m, "differently-signed build")
    }

    // ---- the mapping --------------------------------------------------------

    @Test
    fun fromLookupMapsUnknownAuthorityToUnreachable() {
        val cause = IllegalArgumentException("Unknown authority $authority")
        val e = BridgeError.fromLookup(authority, cause)
        assertIs<BridgeError.ProxyUnreachable>(e)
        assertEquals(authority, e.authority)
        assertSame(cause, e.cause, "the platform exception must survive — it is the only evidence")
    }

    @Test
    fun fromLookupMapsSecurityExceptionToDenied() {
        val cause = SecurityException("refused")
        val e = BridgeError.fromLookup(authority, cause)
        assertIs<BridgeError.PermissionDenied>(e)
        assertSame(cause, e.cause)
    }

    @Test
    fun fromLookupKeepsThePlatformWordsForAnythingElse() {
        // An unmapped failure must stay reportable verbatim rather than being dressed up as one of
        // the two known causes — the same rule InstallResultReceiver.describe follows.
        val cause = IllegalStateException("something we have not seen")
        val e = BridgeError.fromLookup(authority, cause)
        assertIs<BridgeError.OperationFailed>(e)
        assertContains(e.message!!, "IllegalStateException")
        assertContains(e.message!!, "something we have not seen")
        assertSame(cause, e.cause)
    }

    @Test
    fun theTwoCausesAreStillDistinguishable() {
        // The remedies converge on "regenerate and reinstall" today, but a future build with a real
        // remedy for one of them must be able to tell them apart. Collapsing them into one error
        // type would be a regression this test exists to catch.
        val unreachable: BridgeError = BridgeError.ProxyUnreachable(authority, RuntimeException())
        val denied: BridgeError = BridgeError.PermissionDenied(authority, RuntimeException())
        assertTrue(unreachable::class !== denied::class)
        assertTrue(unreachable.message != denied.message)
        // Both are BridgeError, so callers can catch either without knowing which.
        assertFailsWith<BridgeError> { throw unreachable }
        assertFailsWith<BridgeError> { throw denied }
    }
}
