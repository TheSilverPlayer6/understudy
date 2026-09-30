package dev.understudy.proxytpl.bridge

import java.util.concurrent.ConcurrentHashMap

/**
 * The per-uid memo used by [ProxyFileBridge.enforceCaller], holding **positive verdicts only**.
 *
 * Not caching a rejection looks like a missed optimisation. It is the whole point of this class,
 * and the reason it exists separately from the provider is so the invariant can be tested
 * directly rather than argued about: caching a negative turns a *transient* lookup miss into a
 * *permanent* lockout of the legitimate owner.
 *
 * The transient miss is real. `enforceCaller` authenticates a caller by walking its signing
 * certificates through `PackageManager`, and every `PackageManager` query from an app targeting
 * API 30+ is subject to **package-visibility filtering**. The caller does become visible — the
 * platform's automatic-visibility list includes "any app that accesses a content provider in your
 * app" — but that bookkeeping is posted to a handler in `ActivityManagerService`, so on the very
 * first bridge call there is a window in which `getPackagesForUid(callingUid)` returns `null` or
 * an empty array. With a cached negative, that one racing call condemns every call after it:
 * `SecurityException` forever, for the exact install that generated the proxy, with no way to
 * recover short of reinstalling it. A user would see "Understudy stopped working" and nothing in
 * the UI could explain it.
 *
 * The asymmetry in cost is what makes this safe:
 *  - a false negative costs one extra certificate walk on the *next* call from an
 *    already-authorised uid, once, and then it is cached;
 *  - a false *positive* would be a security hole, so nothing here weakens the check itself —
 *    `record(uid, false)` is a deliberate no-op, not a forgotten branch.
 *
 * An unauthorised caller hammering the provider re-runs the certificate walk each time. That is
 * the attacker's bill, not ours, and the walk is a bounded loop over the packages behind one uid.
 *
 * Deliberately free of Android types so it can be unit-tested on the JVM without Robolectric and
 * so it adds nothing but a few hundred bytes to the generated proxy's dex.
 */
class CallerVerdictCache {

    /**
     * Values are always [true]; the map is used as a concurrent set because
     * `ConcurrentHashMap.newKeySet()` is API 24+ and this module stays within framework APIs that
     * exist on every level the proxy can run on.
     */
    private val authorised = ConcurrentHashMap<Int, Boolean>()

    /** True only when [uid] has previously been verified as the generator. Never remembered otherwise. */
    fun isAuthorised(uid: Int): Boolean = authorised[uid] == true

    /**
     * Records the outcome of a verification. A [authorised] `false` is **discarded** — see the
     * class documentation for why that is a correctness requirement and not an oversight.
     */
    fun record(uid: Int, authorised: Boolean) {
        if (authorised) this.authorised[uid] = true
    }

    /** How many uids are currently remembered as authorised. Diagnostics and tests only. */
    fun authorisedCount(): Int = authorised.size

    /** Drops every verdict. Nothing outside a test should need this. */
    fun clear() {
        authorised.clear()
    }
}
