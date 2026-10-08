package com.superz.iptvplayer.ui.theme

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.superz.iptvplayer.data.PlaylistRepository
import com.superz.iptvplayer.data.db.Playlist
import com.superz.iptvplayer.data.remote.OriaRemote
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * v2.1.0 — THE PREMIUM FEATURE SETS THE GATES OPEN OR CLOSED ON.
 *
 * (user: "نضيف تفعيل premium على ميزات اخرى في التطبيق… المشاهدة بدون
 * انترنت… تحميل فيديوهات… تسجيل القنوات… يسمح للمستخدم العادي
 * استخدامها مرة واحدة فقط")
 */
enum class PremiumFeature { OFFLINE_VIEW, DOWNLOAD, RECORD }

/**
 * v2.1.0 — THE PURE PREMIUM RULES (unit-tested in PremiumPolicyTest).
 *
 * The whole subscription model on paper — the PremiumLayout /
 * AnnouncementPolicy pattern: no Android, no Compose, provable:
 *
 *  • PREMIUM IS DATE-BASED (user: "تعتمد تفعيلها بتاريخ بداية ونهاية
 *    حساب xtream… عند تاريخ نهاية حسابه xtream هو تاريخ نهاية نسخة
 *    premium"): the app is premium exactly while it holds an account whose
 *    server IS the panel's premium host AND that account's Xtream
 *    exp_date is still in the future. The day the account expires, the
 *    whole premium edition ends with it — every gate closes again.
 *  • An exp_date the panel did not report (null) = no known end → the
 *    account keeps premium open (the host IS the panel's own locked
 *    premium server — it would not hand out a dead line).
 *  • FREE ACCOUNT LIMIT = 3 (user: "يمكن للمستخدم العادي تشغيل ثلاث
 *    حسابات… اما المستخدم premium عدد غير محدود") — counted over ALL
 *    stored accounts; premium holders are unlimited.
 *  • ONE free trial per gated feature (user: "استخدامها مرة واحدة فقط
 *    و بعدها يطلب منه تفعيل اشتراك").
 */
object PremiumPolicy {

    /** Free users may store this many accounts. */
    const val FREE_ACCOUNT_LIMIT = 3

    /** Uses of each gated feature allowed before a subscription is asked. */
    const val FREE_TRIALS_PER_FEATURE = 1

    /** The minimal account facts the rules need (mapped from Playlist). */
    data class Account(
        val server: String? = null,
        val expiryDate: Long? = null,   // epoch seconds
        val createdAt: Long = 0L,       // epoch millis
        val name: String = ""
    )

    /** The computed subscription state. */
    data class Status(
        val active: Boolean = false,
        val sourceName: String? = null,
        val expiryDate: Long? = null,   // epoch seconds
        val memberSince: Long? = null,  // epoch millis
        val daysLeft: Int? = null       // only when active
    )

    val INACTIVE = Status()

    /**
     * THE RULE: premium while a premium-host account exists with its
     * exp_date (if any) still ahead. Among several such accounts the one
     * with the LATEST expiry wins (a re-purchase replaces the old line,
     * like any subscription). [nowEpochSeconds] is injectable for tests.
     */
    fun status(premiumHost: String?, accounts: List<Account>, nowEpochSeconds: Long): Status {
        if (premiumHost.isNullOrBlank()) return INACTIVE
        val matches = accounts.filter { sameHost(it.server, premiumHost) }
        if (matches.isEmpty()) return INACTIVE
        val best = matches.maxWithOrNull(compareBy { it.expiryDate ?: Long.MAX_VALUE })!!
        val active = (best.expiryDate == null) || best.expiryDate > nowEpochSeconds
        val daysLeft = if (active) {
            best.expiryDate?.let { exp ->
                ((exp - nowEpochSeconds + DAY_SECONDS - 1) / DAY_SECONDS).toInt().coerceAtLeast(0)
            }
        } else null
        return Status(
            active = active,
            sourceName = best.name.ifBlank { null },
            expiryDate = best.expiryDate,
            memberSince = if (best.createdAt > 0) best.createdAt else null,
            daysLeft = daysLeft
        )
    }

    /** True when the free plan's account ceiling is reached. */
    fun accountLimitReached(accountCount: Int, premiumActive: Boolean): Boolean =
        !premiumActive && accountCount >= FREE_ACCOUNT_LIMIT

    /** True while the feature still has free trial uses left. */
    fun trialAvailable(usedTimes: Int): Boolean = usedTimes < FREE_TRIALS_PER_FEATURE

    /**
     * Host comparison, identical normalization to [VuAccountTier.isPremium]
     * (scheme, case, trailing slash) — "http://darplayer.xyz:8080/" (the
     * panel's host) and the playlist's stored form are one string.
     */
    fun sameHost(server: String?, premiumHost: String): Boolean {
        if (premiumHost.isBlank() || server.isNullOrBlank()) return false
        return normalize(server) == normalize(premiumHost)
    }

    private const val DAY_SECONDS = 86_400L

    private fun normalize(url: String): String =
        url.trim()
            .lowercase()
            .removePrefix("https://")
            .removePrefix("http://")
            .trimEnd('/')
}

/**
 * v2.1.0 — THE LIVE PREMIUM STATE (the OriaBranding pattern: Compose
 * snapshot state, so every gate/button/announcement in the app flips the
 * moment the database or the panel config changes).
 *
 * One app-scoped collector watches `playlistsFlow()` — adding, deleting,
 * editing, syncing (the Xtream expiry lands through updateAccountInfo) or
 * re-logging an account re-evaluates the whole subscription instantly.
 * The premium HOST is re-read from [OriaRemote.current] on every refresh,
 * and MainActivity pokes [recompute] when a new panel config lands
 * (configEpoch), so a host change re-tiers the app live too.
 *
 * Free-trial counters live in SharedPreferences ("oria_premium") — one
 * use per feature, forever: [request] returns true (and BURNS the trial)
 * for a free user's first use of a feature; from then on only an active
 * subscription passes.
 */
object PremiumAccess {

    /** The whole premium edition is open right now (date-verified). */
    var active by mutableStateOf(false)
        private set

    /** The premium account's Xtream exp_date (epoch seconds), if known. */
    var expiryDate by mutableStateOf<Long?>(null)
        private set

    /** Whole days left on the subscription (only when active). */
    var daysLeft by mutableStateOf<Int?>(null)
        private set

    /** When the premium account was added (epoch millis), if known. */
    var memberSince by mutableStateOf<Long?>(null)
        private set

    /** The premium account's display name (shown in the info dialog). */
    var sourceAccountName by mutableStateOf<String?>(null)
        private set

    /** Live count of stored accounts (drives the free-plan limit gate). */
    var accountCount by mutableIntStateOf(0)
        private set

    /** Times each gated feature has been used on the free plan. */
    private val trialsUsed = mutableMapOf<PremiumFeature, Int>()
    private var prefs: android.content.SharedPreferences? = null
    private var cached: List<Playlist> = emptyList()
    private var scope: CoroutineScope? = null

    /**
     * IPTVApp.onCreate: load the persisted trial counters and start the
     * playlist watcher. Idempotent — safe to call on every process start.
     */
    fun start(context: Context) {
        if (scope != null) return
        val ctx = context.applicationContext
        prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        PremiumFeature.entries.forEach { f ->
            trialsUsed[f] = if (prefs?.getBoolean(trialKey(f), false) == true) 1 else 0
        }
        val app = com.superz.iptvplayer.IPTVApp.get()
        val repo = PlaylistRepository.get(app)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { sc ->
            sc.launch {
                repo.playlistsFlow().collect { list ->
                    cached = list
                    refresh(list)
                }
            }
        }
    }

    /**
     * Re-evaluate the subscription from the cached account list — called
     * by the flow collector on every DB change and by MainActivity when a
     * fresh panel config (configEpoch) changes the premium host.
     */
    fun recompute() = refresh(cached)

    private fun refresh(playlists: List<Playlist>) {
        accountCount = playlists.size
        val status = PremiumPolicy.status(
            premiumHost = OriaRemote.current.premium.host,
            accounts = playlists.map {
                PremiumPolicy.Account(
                    server = it.server,
                    expiryDate = it.expiryDate,
                    createdAt = it.createdAt,
                    name = it.name
                )
            },
            nowEpochSeconds = System.currentTimeMillis() / 1000L
        )
        active = status.active
        expiryDate = status.expiryDate
        daysLeft = status.daysLeft
        memberSince = status.memberSince
        sourceAccountName = status.sourceName
    }

    /**
     * THE GATE — ask once, before running a premium feature:
     *  • subscription active → allowed, nothing burned;
     *  • free, trial unused → allowed, the one free use is burned NOW;
     *  • free, trial spent → DENIED — the caller shows the upsell dialog
     *    (user: "بعدها يطلب منه تفعيل اشتراك").
     */
    fun request(feature: PremiumFeature): Boolean {
        if (active) return true
        val used = trialsUsed[feature] ?: 0
        if (!PremiumPolicy.trialAvailable(used)) return false
        trialsUsed[feature] = used + 1
        prefs?.edit()?.putBoolean(trialKey(feature), true)?.apply()
        return true
    }

    /** Free-plan uses burned so far for [feature] (0 while premium). */
    fun usedTimes(feature: PremiumFeature): Int =
        if (active) 0 else trialsUsed[feature] ?: 0

    /** True when the free 3-account ceiling is reached (UI gate helper). */
    fun accountLimitReached(): Boolean =
        PremiumPolicy.accountLimitReached(accountCount, active)

    /** Tests / diagnostics only: forget the burned trials. */
    fun resetTrials() {
        PremiumFeature.entries.forEach { trialsUsed[it] = 0 }
        prefs?.edit()?.clear()?.apply()
    }

    private fun trialKey(feature: PremiumFeature) = "trial_used_${feature.name}"

    private const val PREFS_NAME = "oria_premium"
}
