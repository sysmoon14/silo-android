package org.siloserver.silo.tv.profiles

import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.siloserver.silo.model.profile.Profile
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.repository.ProfileCommitResult
import org.siloserver.silo.repository.ProfileRepository

/** Query parameter naming the profile a `silo://play` or `silo://item` link opens in. */
internal const val TV_DEEP_LINK_PROFILE_PARAM = "profile"

/** Content links that may name a profile. Pairing and party links are account-level. */
private val TV_PROFILE_LINK_HOSTS = setOf("play", "item")

// Profile ids are server-issued UUIDs; anything else is not worth a request.
private val TV_PROFILE_ID_PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9-]{0,63}$")

/** The profile a content link names, or null when it names none (or a malformed one). */
internal fun tvDeepLinkProfileId(host: String?, queryParameter: (String) -> String?): String? {
    if (host?.lowercase() !in TV_PROFILE_LINK_HOSTS) return null
    return queryParameter(TV_DEEP_LINK_PROFILE_PARAM)
        ?.trim()
        ?.takeIf { TV_PROFILE_ID_PATTERN.matches(it) }
}

/** [tvDeepLinkProfileId] for a delivered link. */
internal fun Uri.tvDeepLinkProfileId(): String? = tvDeepLinkProfileId(host, ::getQueryParameter)

/**
 * Opens the profile a content deep link names (`silo://play/<id>?profile=<id>`)
 * without showing Who's Watching, so a shared TV started from a remote, a
 * launcher or home automation plays for the right viewer.
 *
 * Only a profile without a PIN is ever opened this way. A protected profile,
 * one this account doesn't have, or a failed lookup leaves the choice to the
 * picker, exactly as before links could name a profile: a link can never do
 * what the viewer couldn't do with one press on the picker.
 *
 * The caller owns everything around the switch: ending the away interval,
 * re-seeding Watch Next, and navigating into the profile.
 */
class TvDeepLinkProfileSwitch(
    private val hasTemporaryScope: suspend () -> Boolean,
    private val activeProfileId: suspend () -> String?,
    private val captureScope: suspend () -> AuthScopeSnapshot?,
    private val scopeUnchanged: suspend (AuthScopeSnapshot?) -> Boolean,
    private val listProfiles: suspend () -> ApiResult<List<Profile>>,
    private val clearActiveProfile: suspend () -> Unit,
    private val selectProfile: suspend (profileId: String, scope: AuthScopeSnapshot?) -> ProfileCommitResult,
) {
    constructor(
        profileRepository: ProfileRepository,
        tokenManager: TokenManager,
        activeProfileReset: TvActiveProfileReset,
    ) : this(
        hasTemporaryScope = tokenManager::hasTemporaryScope,
        activeProfileId = profileRepository::getActiveProfileId,
        captureScope = profileRepository::captureIdentityScope,
        scopeUnchanged = profileRepository::identityScopeUnchanged,
        listProfiles = profileRepository::listProfiles,
        clearActiveProfile = activeProfileReset::clearActiveProfile,
        selectProfile = { id, scope -> profileRepository.selectProfile(id, profileToken = null, expectedScope = scope) },
    )

    private val mutex = Mutex()

    private val _covering = MutableStateFlow(false)

    /**
     * Raised while a link replaces the profile on screen. The main Activity
     * covers the screen, as it does for a return to Who's Watching, so the
     * previous profile's rows never repaint with the next profile's settings.
     */
    val covering: StateFlow<Boolean> = _covering.asStateFlow()

    fun beginCover() {
        _covering.value = true
    }

    fun endCover() {
        _covering.value = false
    }

    /**
     * Makes [profileId] the active profile. Returns true when it is active
     * afterwards and the link may open in it; false when the picker must
     * decide.
     *
     * [selectionRequired] is true when Profile Selection would otherwise show
     * Who's Watching (launch, return to Silo, or the picker already on
     * screen). Then even the active profile is checked again, because a
     * protected one must ask for its PIN.
     */
    suspend fun activate(profileId: String, selectionRequired: Boolean): Boolean = mutex.withLock {
        // A remote-playback overlay owns identity while it exists.
        if (hasTemporaryScope()) return false
        val activeId = activeProfileId()
        if (activeId == profileId && !selectionRequired) return true

        val listScope = captureScope()
        val profiles = (listProfiles() as? ApiResult.Success)?.data ?: return false
        // A list fetched under an identity since replaced proves nothing about this one.
        if (!scopeUnchanged(listScope)) return false
        val target = profiles.firstOrNull { it.id == profileId } ?: return false
        if (target.hasPin) return false
        if (activeId == profileId) return true

        if (activeId != null) clearActiveProfile()
        // Clearing moved the identity generation, so the commit is guarded by
        // a fresh scope that must still be the same server and sign-in.
        val commitScope = captureScope()
        if (listScope != null && !listScope.isSameAccountAs(commitScope)) return false
        selectProfile(profileId, commitScope) == ProfileCommitResult.Committed
    }

    private fun AuthScopeSnapshot.isSameAccountAs(other: AuthScopeSnapshot?): Boolean =
        other != null && other.serverId == serverId && other.credentialEpoch == credentialEpoch
}
