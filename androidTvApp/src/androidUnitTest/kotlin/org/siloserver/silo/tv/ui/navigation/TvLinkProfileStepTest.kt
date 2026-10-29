package org.siloserver.silo.tv.ui.navigation

import kotlin.test.Test
import kotlin.test.assertEquals

class TvLinkProfileStepTest {
    private val picker = TvRoute.ProfileSelection.route
    private val main = TvRoute.Main.route

    private fun step(
        route: String,
        active: String?,
        returnPending: Boolean = false,
        pickerOffered: Boolean = false,
    ) = tvLinkProfileStep(
        route = route,
        linkProfileId = "kids",
        activeProfileId = active,
        returnPending = returnPending,
        pickerOffered = pickerOffered,
    )

    @Test
    fun opensInTheActiveProfile() {
        assertEquals(TvLinkProfileStep.Open, step(main, active = "kids"))
    }

    @Test
    fun switchesFromAnotherProfileOrThePicker() {
        assertEquals(TvLinkProfileStep.Switch, step(main, active = "adults"))
        assertEquals(TvLinkProfileStep.Switch, step(picker, active = null))
    }

    @Test
    fun waitsForSignInAndForTheReturnRule() {
        assertEquals(TvLinkProfileStep.Wait, step(TvRoute.Login.ROUTE, active = null))
        assertEquals(TvLinkProfileStep.Wait, step(TvRoute.CreateProfile.route, active = null))
        assertEquals(TvLinkProfileStep.Wait, step(main, active = "adults", returnPending = true))
    }

    @Test
    fun aLinkHandedToThePickerOpensOnlyInItsProfile() {
        assertEquals(TvLinkProfileStep.Wait, step(picker, active = null, pickerOffered = true))
        assertEquals(TvLinkProfileStep.Open, step(main, active = "kids", pickerOffered = true))
        assertEquals(TvLinkProfileStep.Drop, step(main, active = "adults", pickerOffered = true))
    }
}
