package com.mapbox.navigation.trip.notification

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.Resources
import android.text.SpannableString
import android.text.TextUtils
import android.text.format.DateFormat
import android.widget.RemoteViews
import com.mapbox.api.directions.v5.models.BannerInstructions
import com.mapbox.api.directions.v5.models.BannerText
import com.mapbox.navigation.base.formatter.DistanceFormatter
import com.mapbox.navigation.base.options.NavigationOptions
import com.mapbox.navigation.base.trip.model.RouteLegProgress
import com.mapbox.navigation.base.trip.model.RouteProgress
import com.mapbox.navigation.trip.notification.utils.time.TimeFormatter
import com.mapbox.navigation.utils.NOTIFICATION_ID
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.verify
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

private const val STOP_SESSION = "Stop session"
private const val END_NAVIGATION = "End Navigation"
private const val FORMAT_STRING = "%s 454545 ETA"
private const val MANEUVER_TYPE = "MANEUVER TYPE"
private const val MANEUVER_MODIFIER = "MANEUVER MODIFIER"

class MapboxTripNotificationTest {

    private lateinit var notification: MapboxTripNotification
    private lateinit var mockedContext: Context
    private lateinit var collapsedViews: RemoteViews
    private lateinit var expandedViews: RemoteViews
    private val navigationOptions: NavigationOptions = mockk(relaxed = true)
    private val distanceSpannable: SpannableString = mockk()
    private val distanceFormatter: DistanceFormatter

    init {
        val distanceSlot = slot<Double>()
        distanceFormatter = mockk()
        every { distanceFormatter.formatDistance(capture(distanceSlot)) } returns distanceSpannable
        every { navigationOptions.distanceFormatter } returns distanceFormatter
    }

    @Before
    fun setUp() {
        mockkStatic(DateFormat::class)
        mockkStatic(PendingIntent::class)
        mockedContext = createContext()
        mockRemoteViews()
        notification = MapboxTripNotification(
            mockedContext,
            navigationOptions
        )
    }

    private fun mockRemoteViews() {
        mockkObject(RemoteViewsProvider)
        collapsedViews = mockk(relaxUnitFun = true)
        expandedViews = mockk(relaxUnitFun = true)
        every {
            RemoteViewsProvider.createRemoteViews(
                any(),
                R.layout.collapsed_navigation_notification_layout
            )
        } returns collapsedViews
        every {
            RemoteViewsProvider.createRemoteViews(
                any(),
                R.layout.expanded_navigation_notification_layout
            )
        } returns expandedViews
    }

    @Test
    fun generateSanityTest() {
        assertNotNull(notification)
    }

    private fun createContext(): Context {
        val mockedContext = mockk<Context>()
        val mockedBroadcastReceiverIntent = mockk<Intent>()
        val mockPendingIntentForActivity = mockk<PendingIntent>(relaxed = true)
        val mockPendingIntentForBroadcast = mockk<PendingIntent>(relaxed = true)
        val mockedConfiguration = Configuration()
        mockedConfiguration.locale = Locale("en")
        val mockedResources = mockk<Resources>(relaxed = true)
        every { mockedResources.configuration } returns (mockedConfiguration)
        every { mockedContext.resources } returns (mockedResources)
        val mockedPackageManager = mockk<PackageManager>(relaxed = true)
        every { mockedContext.packageManager } returns (mockedPackageManager)
        every { mockedContext.packageName } returns ("com.mapbox.navigation.trip.notification")
        every { mockedContext.getString(any()) } returns FORMAT_STRING
        every { mockedContext.getString(R.string.stop_session) } returns STOP_SESSION
        every { mockedContext.getString(R.string.end_navigation) } returns END_NAVIGATION
        val notificationManager = mockk<NotificationManager>(relaxed = true)
        every { mockedContext.getSystemService(Context.NOTIFICATION_SERVICE) } returns (notificationManager)
        every { DateFormat.is24HourFormat(mockedContext) } returns (false)
        every {
            PendingIntent.getActivity(
                mockedContext,
                any(),
                any(),
                any()
            )
        } returns (mockPendingIntentForActivity)
        every {
            PendingIntent.getBroadcast(
                mockedContext,
                any(),
                any(),
                any()
            )
        } returns (mockPendingIntentForBroadcast)
        every {
            mockedContext.registerReceiver(
                any(),
                any()
            )
        } returns (mockedBroadcastReceiverIntent)
        every { mockedContext.unregisterReceiver(any()) } just Runs
        return mockedContext
    }

    @Test
    fun whenTripStartedThenRegisterReceiverCalledOnce() {
        notification.onTripSessionStarted()
        verify(exactly = 1) { mockedContext.registerReceiver(any(), any()) }
    }

    @Test
    fun whenTripStoppedThenCleanupIsDone() {
        val notificationManager =
            mockedContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notification.onTripSessionStopped()

        verify(exactly = 1) { mockedContext.unregisterReceiver(any()) }
        verify(exactly = 1) { notificationManager.cancel(NOTIFICATION_ID) }
        assertEquals(
            true,
            MapboxTripNotification.notificationActionButtonChannel.isClosedForReceive
        )
        assertEquals(true, MapboxTripNotification.notificationActionButtonChannel.isClosedForSend)
    }

    @Test
    fun whenGetNotificationCalledThenNavigationNotificationProviderInteractedOnlyOnce() {
        mockNotificationCreation()

        notification.getNotification()

        verify(exactly = 1) { NavigationNotificationProvider.buildNotification(any()) }

        notification.getNotification()
        notification.getNotification()

        verify(exactly = 1) { NavigationNotificationProvider.buildNotification(any()) }
    }

    @Test
    fun whenUpdateNotificationCalledThenPrimaryTextIsSetToRemoteViews() {
        val routeProgress = mockk<RouteProgress>(relaxed = true)
        val primaryText = { "Primary Text" }
        val bannerText = mockBannerText(routeProgress, primaryText)
        mockUpdateNotificationAndroidInteractions()

        notification.updateNotification(routeProgress)

        verify(exactly = 1) { bannerText.text() }
        verify(exactly = 1) { collapsedViews.setTextViewText(any(), primaryText()) }
        verify(exactly = 1) { expandedViews.setTextViewText(any(), primaryText()) }
        verify(exactly = 1) { expandedViews.setTextViewText(any(), END_NAVIGATION) }
        verify(exactly = 0) { expandedViews.setTextViewText(any(), STOP_SESSION) }
        assertEquals(notification.currentManeuverType, MANEUVER_TYPE)
        assertEquals(notification.currentManeuverModifier, MANEUVER_MODIFIER)
    }

    @Test
    fun whenUpdateNotificationCalledThenDistanceTextIsSetToRemoteViews() {
        val routeProgress = mockk<RouteProgress>(relaxed = true)
        val distance = 30f
        val duration = 112L
        mockLegProgress(routeProgress, distance, duration)
        val distanceSlot1 = slot<SpannableString>()
        val distanceSlot2 = slot<SpannableString>()
        every { collapsedViews.setTextViewText(any(), capture(distanceSlot1)) } just Runs
        every { expandedViews.setTextViewText(any(), capture(distanceSlot2)) } just Runs
        mockUpdateNotificationAndroidInteractions()

        notification.updateNotification(routeProgress)

        verify(exactly = 1) { collapsedViews.setTextViewText(any(), distanceSpannable) }
        verify(exactly = 1) { expandedViews.setTextViewText(any(), distanceSpannable) }
        assertEquals(distanceSpannable, distanceSlot1.captured)
        assertEquals(distanceSpannable, distanceSlot2.captured)
    }

    @Test
    fun whenUpdateNotificationCalledThenArrivalTimeIsSetToRemoteViews() {
        val routeProgress = mockk<RouteProgress>(relaxed = true)
        val distance = 30f
        val duration = 112L
        mockLegProgress(routeProgress, distance, duration)
        mockUpdateNotificationAndroidInteractions()
        val suffix = "this is nice formatting"
        mockTimeFormatter(suffix)
        val result = String.format(FORMAT_STRING, suffix + duration.toDouble().toString())

        notification.updateNotification(routeProgress)

        verify(exactly = 1) { collapsedViews.setTextViewText(any(), result) }
        verify(exactly = 1) { expandedViews.setTextViewText(any(), result) }
    }

    @Test
    fun whenUpdateNotificationCalledTwiceWithSameDataThenRemoteViewAreNotUpdatedTwice() {
        val routeProgress = mockk<RouteProgress>(relaxed = true)
        val primaryText = { "Primary Text" }
        val bannerText = mockBannerText(routeProgress, primaryText)
        mockUpdateNotificationAndroidInteractions()

        notification.updateNotification(routeProgress)

        verify(exactly = 1) { bannerText.text() }
        verify(exactly = 1) { collapsedViews.setTextViewText(any(), primaryText()) }
        verify(exactly = 1) { expandedViews.setTextViewText(any(), primaryText()) }

        notification.updateNotification(routeProgress)

        verify(exactly = 2) { bannerText.text() }
        verify(exactly = 1) { collapsedViews.setTextViewText(any(), primaryText()) }
        verify(exactly = 1) { expandedViews.setTextViewText(any(), primaryText()) }
        assertEquals(notification.currentManeuverType, MANEUVER_TYPE)
        assertEquals(notification.currentManeuverModifier, MANEUVER_MODIFIER)
    }

    @Test
    fun whenUpdateNotificationCalledTwiceWithDifferentDataThenRemoteViewUpdatedTwice() {
        val routeProgress = mockk<RouteProgress>(relaxed = true)
        val initialPrimaryText = "Primary Text"
        val changedPrimaryText = "Changed Primary Text"
        var primaryText = initialPrimaryText
        val primaryTextLambda = { primaryText }
        val bannerText = mockBannerText(routeProgress, primaryTextLambda)
        mockUpdateNotificationAndroidInteractions()

        notification.updateNotification(routeProgress)
        primaryText = changedPrimaryText
        notification.updateNotification(routeProgress)

        verify(exactly = 2) { bannerText.text() }
        verify(exactly = 1) { collapsedViews.setTextViewText(any(), initialPrimaryText) }
        verify(exactly = 1) { expandedViews.setTextViewText(any(), initialPrimaryText) }
        verify(exactly = 1) { collapsedViews.setTextViewText(any(), changedPrimaryText) }
        verify(exactly = 1) { expandedViews.setTextViewText(any(), changedPrimaryText) }
        assertEquals(notification.currentManeuverType, MANEUVER_TYPE)
        assertEquals(notification.currentManeuverModifier, MANEUVER_MODIFIER)
    }

    @Test
    fun whenGoThroughStartUpdateStopCycleThenNotificationCacheDropped() {
        val routeProgress = mockk<RouteProgress>(relaxed = true)
        val primaryText = { "Primary Text" }
        val bannerText = mockBannerText(routeProgress, primaryText)
        mockUpdateNotificationAndroidInteractions()

        notification.onTripSessionStarted()
        notification.updateNotification(routeProgress)
        notification.onTripSessionStopped()
        notification.onTripSessionStarted()

        verify(exactly = 1) { bannerText.text() }
        verify(exactly = 1) { collapsedViews.setTextViewText(any(), primaryText()) }
        verify(exactly = 1) { expandedViews.setTextViewText(any(), primaryText()) }
        assertNull(notification.currentManeuverType)
        assertNull(notification.currentManeuverModifier)

        notification.updateNotification(routeProgress)

        verify(exactly = 2) { bannerText.text() }
        verify(exactly = 2) { collapsedViews.setTextViewText(any(), primaryText()) }
        verify(exactly = 2) { expandedViews.setTextViewText(any(), primaryText()) }
        assertEquals(notification.currentManeuverType, MANEUVER_TYPE)
        assertEquals(notification.currentManeuverModifier, MANEUVER_MODIFIER)
    }

    @Test
    fun whenGoThroughStartUpdateStopCycleThenStartStopSessionDontAffectRemoteViews() {
        val routeProgress = mockk<RouteProgress>(relaxed = true)
        val primaryText = { "Primary Text" }
        val bannerText = mockBannerText(routeProgress, primaryText)
        mockUpdateNotificationAndroidInteractions()

        notification.onTripSessionStarted()
        notification.updateNotification(routeProgress)

        verify(exactly = 1) { bannerText.text() }
        verify(exactly = 1) { collapsedViews.setTextViewText(any(), primaryText()) }
        verify(exactly = 1) { expandedViews.setTextViewText(any(), primaryText()) }
        assertEquals(notification.currentManeuverType, MANEUVER_TYPE)
        assertEquals(notification.currentManeuverModifier, MANEUVER_MODIFIER)

        notification.onTripSessionStopped()
        notification.onTripSessionStarted()

        verify(exactly = 1) { bannerText.text() }
        verify(exactly = 1) { collapsedViews.setTextViewText(any(), primaryText()) }
        verify(exactly = 1) { expandedViews.setTextViewText(any(), primaryText()) }

        notification.onTripSessionStopped()

        verify(exactly = 1) { bannerText.text() }
        verify(exactly = 1) { collapsedViews.setTextViewText(any(), primaryText()) }
        verify(exactly = 1) { expandedViews.setTextViewText(any(), primaryText()) }
        assertNull(notification.currentManeuverType)
        assertNull(notification.currentManeuverModifier)
    }

    @Test
    fun whenFreeDrive() {
        val routeProgress = mockk<RouteProgress>(relaxed = true)
        every { routeProgress.route() } returns null
        mockUpdateNotificationAndroidInteractions()

        notification.onTripSessionStarted()
        notification.updateNotification(routeProgress)

        verify(exactly = 0) { expandedViews.setTextViewText(any(), END_NAVIGATION) }
        verify(exactly = 1) { expandedViews.setTextViewText(any(), STOP_SESSION) }
    }

    private fun mockUpdateNotificationAndroidInteractions() {
        mockkStatic(TextUtils::class)
        val slot = slot<CharSequence>()
        every { TextUtils.isEmpty(capture(slot)) } answers { slot.captured.isEmpty() }

        mockNotificationCreation()
    }

    private fun mockNotificationCreation() {
        mockkObject(NavigationNotificationProvider)
        val notificationMock = mockk<Notification>()
        every { NavigationNotificationProvider.buildNotification(any()) } returns notificationMock
    }

    private fun mockBannerText(
        routeProgress: RouteProgress,
        primaryText: () -> String,
        primaryType: () -> String = { MANEUVER_TYPE },
        primaryModifier: () -> String = { MANEUVER_MODIFIER }
    ): BannerText {
        val bannerText = mockk<BannerText>()
        val bannerInstructions = mockk<BannerInstructions>()
        every { routeProgress.bannerInstructions() } returns bannerInstructions
        every { bannerInstructions.primary() } returns bannerText
        every { bannerText.text() } answers { primaryText() }
        every { bannerText.type() } answers { primaryType() }
        every { bannerText.modifier() } answers { primaryModifier() }
        return bannerText
    }

    @Suppress("SameParameterValue")
    private fun mockLegProgress(
        routeProgress: RouteProgress,
        distance: Float,
        duration: Long
    ): RouteLegProgress {
        val currentLegProgress = mockk<RouteLegProgress>(relaxed = true)
        every { routeProgress.currentLegProgress() } returns currentLegProgress
        every { currentLegProgress.currentStepProgress()?.distanceRemaining() } returns distance
        every { currentLegProgress.durationRemaining() } returns duration
        return currentLegProgress
    }

    private fun mockTimeFormatter(@Suppress("SameParameterValue") suffix: String) {
        mockkStatic(TimeFormatter::class)
        val durationSlot = slot<Double>()
        every {
            TimeFormatter.formatTime(any(), capture(durationSlot), any(), any())
        } answers { "$suffix${durationSlot.captured}" }
    }
}
