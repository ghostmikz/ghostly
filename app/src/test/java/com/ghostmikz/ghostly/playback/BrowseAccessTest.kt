package com.ghostmikz.ghostly.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowseAccessTest {
    private val own = "com.ghostmikz.ghostly"
    private val auto = "com.google.android.projection.gearhead"

    private fun may(pkg: String, trusted: Boolean = false, verified: Boolean = true, genuine: Boolean = true, system: Boolean = false) =
        BrowseAccess.decide(own, pkg, trusted, verified, systemApp = { system }, genuine = { genuine })

    @Test fun ghostlyItselfBrowses() = assertTrue(may(own))

    @Test fun systemBrowses() = assertTrue(may("com.android.systemui", trusted = true))

    @Test fun androidAutoBrowses() = assertTrue(may(auto))

    @Test fun fakeAndroidAutoDoesNot() = assertFalse(may(auto, genuine = false))

    @Test fun unverifiedNameDoesNot() = assertFalse(may(auto, verified = false))

    @Test fun otherAppsDoNot() = assertFalse(may("com.example.anything"))

    @Test fun appsThatCameWithThePhoneBrowse() = assertTrue(may("com.android.bluetooth", system = true))

    @Test fun unverifiedSystemNameDoesNot() = assertFalse(may("com.android.bluetooth", system = true, verified = false))
}
