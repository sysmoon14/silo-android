package org.siloserver.silo.tv.profiles

import kotlinx.coroutines.test.runTest
import org.siloserver.silo.model.profile.Profile
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.repository.ProfileCommitResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TvDeepLinkProfileSwitchTest {
    private val kids = Profile(id = "11111111-aaaa-4bbb-8ccc-000000000001", name = "Kids")
    private val adults = Profile(id = "22222222-aaaa-4bbb-8ccc-000000000002", name = "Adults", hasPin = true)
    private val guest = Profile(id = "33333333-aaaa-4bbb-8ccc-000000000003", name = "Guest")

    private class Harness(
        var active: String?,
        var profiles: ApiResult<List<Profile>>,
        var temporaryScope: Boolean = false,
        var scopeHolds: Boolean = true,
        var commit: ProfileCommitResult = ProfileCommitResult.Committed,
    ) {
        var generation = 1L
        val calls = mutableListOf<String>()

        fun scope() = AuthScopeSnapshot(
            serverId = "server",
            profileId = active,
            serverUrl = "https://silo.test",
            profileToken = null,
            identityGeneration = generation,
            credentialEpoch = 7L,
        )

        val switch = TvDeepLinkProfileSwitch(
            hasTemporaryScope = { temporaryScope },
            activeProfileId = { active },
            captureScope = { scope() },
            scopeUnchanged = { scopeHolds },
            listProfiles = {
                calls += "list"
                profiles
            },
            clearActiveProfile = {
                calls += "clear"
                active = null
                generation++
            },
            selectProfile = { id, scope ->
                calls += "select:$id@${scope?.identityGeneration}"
                if (commit == ProfileCommitResult.Committed) active = id
                commit
            },
        )
    }

    private fun household() = ApiResult.Success(listOf(kids, adults, guest))

    @Test
    fun activeProfileOpensWithoutAskingTheServer() = runTest {
        val h = Harness(active = kids.id, profiles = household())

        assertTrue(h.switch.activate(kids.id, selectionRequired = false))
        assertEquals(emptyList<String>(), h.calls)
    }

    @Test
    fun switchesToAnUnprotectedProfileWithAFreshScope() = runTest {
        val h = Harness(active = guest.id, profiles = household())

        assertTrue(h.switch.activate(kids.id, selectionRequired = false))
        // The previous profile's state is cleared first, and the commit is
        // guarded by the scope captured after that clear.
        assertEquals(listOf("list", "clear", "select:${kids.id}@2"), h.calls)
        assertEquals(kids.id, h.active)
    }

    @Test
    fun opensFromThePickerWithNothingToClear() = runTest {
        val h = Harness(active = null, profiles = household())

        assertTrue(h.switch.activate(kids.id, selectionRequired = true))
        assertEquals(listOf("list", "select:${kids.id}@1"), h.calls)
    }

    @Test
    fun neverOpensAProfileWithAPin() = runTest {
        val h = Harness(active = kids.id, profiles = household())

        assertFalse(h.switch.activate(adults.id, selectionRequired = false))
        assertEquals(listOf("list"), h.calls)
        assertEquals(kids.id, h.active)
    }

    @Test
    fun aProtectedActiveProfileStillAsksForItsPinWhenSelectionIsRequired() = runTest {
        val h = Harness(active = adults.id, profiles = household())

        assertFalse(h.switch.activate(adults.id, selectionRequired = true))
    }

    @Test
    fun anUnprotectedActiveProfileSkipsTheReturnPicker() = runTest {
        val h = Harness(active = kids.id, profiles = household())

        assertTrue(h.switch.activate(kids.id, selectionRequired = true))
        assertEquals(listOf("list"), h.calls)
    }

    @Test
    fun unknownProfileOrFailedListLeavesTheChoiceToThePicker() = runTest {
        val unknown = Harness(active = kids.id, profiles = household())
        assertFalse(unknown.switch.activate("44444444-aaaa-4bbb-8ccc-000000000004", selectionRequired = false))

        val offline = Harness(active = kids.id, profiles = ApiResult.NetworkError(RuntimeException("offline")))
        assertFalse(offline.switch.activate(guest.id, selectionRequired = false))
        assertEquals(kids.id, offline.active)
    }

    @Test
    fun aListFromAReplacedIdentityIsNotTrusted() = runTest {
        val h = Harness(active = kids.id, profiles = household(), scopeHolds = false)

        assertFalse(h.switch.activate(guest.id, selectionRequired = false))
        assertEquals(listOf("list"), h.calls)
    }

    @Test
    fun aRemotePlaybackOverlayIsLeftAlone() = runTest {
        val h = Harness(active = kids.id, profiles = household(), temporaryScope = true)

        assertFalse(h.switch.activate(guest.id, selectionRequired = false))
        assertEquals(emptyList<String>(), h.calls)
    }

    @Test
    fun aRefusedCommitReportsFailure() = runTest {
        val h = Harness(active = kids.id, profiles = household(), commit = ProfileCommitResult.ScopeChanged)

        assertFalse(h.switch.activate(guest.id, selectionRequired = false))
    }

    @Test
    fun readsTheProfileOnlyFromContentLinks() {
        val query = mapOf("profile" to " ${kids.id} ")

        assertEquals(kids.id, tvDeepLinkProfileId("play", query::get))
        assertEquals(kids.id, tvDeepLinkProfileId("ITEM", query::get))
        assertNull(tvDeepLinkProfileId("device", query::get))
        assertNull(tvDeepLinkProfileId(null, query::get))
        assertNull(tvDeepLinkProfileId("play", emptyMap<String, String>()::get))
        assertNull(tvDeepLinkProfileId("play", mapOf("profile" to "../profiles")::get))
        assertNull(tvDeepLinkProfileId("play", mapOf("profile" to "")::get))
    }
}
