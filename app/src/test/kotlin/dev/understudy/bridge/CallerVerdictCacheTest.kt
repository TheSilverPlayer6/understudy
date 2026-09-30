package dev.understudy.bridge

import dev.understudy.proxytpl.bridge.CallerVerdictCache
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the one property of [CallerVerdictCache] that is a correctness requirement rather than an
 * optimisation: **rejections are never remembered.**
 *
 * The failure this prevents is invisible in every other test in the project, and it is permanent
 * rather than flaky. `ProxyFileBridge.enforceCaller()` authenticates a caller by walking its
 * signing certificates through `PackageManager`, and from an app targeting API 30+ those queries
 * are subject to package-visibility filtering. The caller does become visible — the platform's
 * automatic list includes "any app that accesses a content provider in your app" — but the
 * bookkeeping is posted to a handler in `ActivityManagerService`, so the first bridge call can
 * race it and resolve no packages at all. Cache that and the legitimate owner is locked out for
 * the lifetime of the proxy process: every subsequent call throws `SecurityException`, the UI can
 * say nothing more specific than "bridge unavailable", and the only recovery is reinstalling the
 * proxy.
 *
 * Only the policy is testable here. The race itself lives in `ActivityManagerService` and needs a
 * device; `CallerAuthPremiseTest` covers the positive path end to end on an emulator.
 */
class CallerVerdictCacheTest {

    private val generatorUid = 1_010_148 // u10_a148 — a secondary-profile app uid
    private val strangerUid = 1_010_999

    @Test
    fun `a fresh cache authorises nobody`() {
        val cache = CallerVerdictCache()
        assertFalse(cache.isAuthorised(generatorUid))
        assertEquals(0, cache.authorisedCount())
    }

    @Test
    fun `a positive verdict is remembered`() {
        val cache = CallerVerdictCache()
        cache.record(generatorUid, authorised = true)
        assertTrue(cache.isAuthorised(generatorUid), "a verified generator must stay verified")
        assertEquals(1, cache.authorisedCount())
    }

    @Test
    fun `a negative verdict is NOT remembered`() {
        val cache = CallerVerdictCache()
        cache.record(strangerUid, authorised = false)

        // Not merely "still false": it must be *absent*, so the next call re-runs the check
        // instead of being answered from the cache. isAuthorised() cannot distinguish those, so
        // the count is what actually pins it.
        assertFalse(cache.isAuthorised(strangerUid))
        assertEquals(0, cache.authorisedCount(), "a rejection must not occupy the cache")
    }

    @Test
    fun `a caller denied once is still eligible to be authorised later`() {
        // The exact sequence the package-visibility race produces: first contact resolves nothing
        // and is refused, then the caller becomes visible and must be let in. A cached negative
        // makes this impossible, which is the bug.
        val cache = CallerVerdictCache()
        cache.record(generatorUid, authorised = false)
        assertFalse(cache.isAuthorised(generatorUid))

        cache.record(generatorUid, authorised = true)
        assertTrue(
            cache.isAuthorised(generatorUid),
            "a transient first-call miss permanently locked out the generator",
        )
    }

    @Test
    fun `verdicts are per uid`() {
        val cache = CallerVerdictCache()
        cache.record(generatorUid, authorised = true)
        cache.record(strangerUid, authorised = false)

        assertTrue(cache.isAuthorised(generatorUid))
        assertFalse(cache.isAuthorised(strangerUid))
        assertEquals(1, cache.authorisedCount(), "only the authorised uid may be stored")
    }

    @Test
    fun `recording the same uid twice is idempotent`() {
        val cache = CallerVerdictCache()
        repeat(3) { cache.record(generatorUid, authorised = true) }
        assertEquals(1, cache.authorisedCount())
        assertTrue(cache.isAuthorised(generatorUid))
    }

    @Test
    fun `clear drops every verdict`() {
        val cache = CallerVerdictCache()
        cache.record(generatorUid, authorised = true)
        cache.record(strangerUid, authorised = true)
        assertEquals(2, cache.authorisedCount())

        cache.clear()
        assertEquals(0, cache.authorisedCount())
        assertFalse(cache.isAuthorised(generatorUid))
    }

    @Test
    fun `concurrent recording converges on the authorised set`() {
        // enforceCaller runs on Binder threads, so the cache is genuinely concurrent. This is not
        // a stress test of the JDK; it is a check that the class stays correct if a future edit
        // swaps the map for something unsynchronised, or starts storing negatives.
        val cache = CallerVerdictCache()
        val uids = (0 until 64).toList()
        val threads = uids.map { uid ->
            Thread {
                repeat(200) {
                    cache.record(uid, authorised = uid % 2 == 0)
                    cache.isAuthorised(uid)
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        val expectedAuthorised = uids.count { it % 2 == 0 }
        assertEquals(expectedAuthorised, cache.authorisedCount())
        for (uid in uids) {
            assertEquals(uid % 2 == 0, cache.isAuthorised(uid), "uid $uid has the wrong verdict")
        }
    }
}
