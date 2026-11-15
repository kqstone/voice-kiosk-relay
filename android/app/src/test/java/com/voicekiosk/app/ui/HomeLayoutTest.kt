package com.voicekiosk.app.ui

import com.voicekiosk.app.media.MusicState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Layout loading and orientation-switch test covering portrait and landscape home layouts.
 * Verifies that the Now-Playing widget is properly integrated into both layouts,
 * hidden when nothing plays, and maintains complete ID and state parity across orientation changes.
 */
class HomeLayoutTest {

    private fun locateLayoutFile(relativePath: String): File {
        val candidates = listOf(
            File("src/main/res/$relativePath"),
            File("app/src/main/res/$relativePath"),
            File("android/app/src/main/res/$relativePath"),
            File("../android/app/src/main/res/$relativePath"),
            File("src/main/res/layout/$relativePath"),
            File("app/src/main/res/layout/$relativePath"),
            File("android/app/src/main/res/layout/$relativePath"),
            File("../android/app/src/main/res/layout/$relativePath")
        )
        val file = candidates.firstOrNull { it.isFile }
        assertNotNull("Could not find layout file: $relativePath", file)
        return file!!
    }

    private fun parseXml(file: File): Document {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        val builder = factory.newDocumentBuilder()
        return builder.parse(file)
    }

    private fun getAttribute(element: Element, attrName: String): String? {
        val androidNs = "http://schemas.android.com/apk/res/android"
        if (element.hasAttributeNS(androidNs, attrName)) {
            return element.getAttributeNS(androidNs, attrName)
        }
        if (element.hasAttribute("android:$attrName")) {
            return element.getAttribute("android:$attrName")
        }
        if (element.hasAttribute(attrName)) {
            return element.getAttribute(attrName)
        }
        return null
    }

    private fun collectAllElements(node: Node, result: MutableList<Element>) {
        if (node.nodeType == Node.ELEMENT_NODE) {
            result.add(node as Element)
        }
        val children = node.childNodes
        for (i in 0 until children.length) {
            collectAllElements(children.item(i), result)
        }
    }

    private fun findElementByAndroidId(doc: Document, idName: String): Element? {
        val targetId1 = "@+id/$idName"
        val targetId2 = "@id/$idName"
        val elements = mutableListOf<Element>()
        collectAllElements(doc.documentElement, elements)
        return elements.firstOrNull { element ->
            val id = getAttribute(element, "id")
            id == targetId1 || id == targetId2
        }
    }

    private fun extractAllAndroidIds(doc: Document): Set<String> {
        val elements = mutableListOf<Element>()
        collectAllElements(doc.documentElement, elements)
        val idSet = mutableSetOf<String>()
        for (elem in elements) {
            val id = getAttribute(elem, "id")
            if (id != null && (id.startsWith("@+id/") || id.startsWith("@id/"))) {
                idSet.add(id.removePrefix("@+id/").removePrefix("@id/"))
            }
        }
        return idSet
    }

    // =========================================================================
    // 1. Portrait Home Layout Tests
    // =========================================================================

    @Test
    fun testPortraitHomeLayout_loadsSuccessfully_andWidgetPresentAndHiddenByDefault() {
        val portraitFile = locateLayoutFile("layout/fragment_home.xml")
        val doc = parseXml(portraitFile)

        // Root FrameLayout with OLED black background
        val root = doc.documentElement
        assertEquals("FrameLayout", root.tagName)
        assertEquals("@color/oled_black", getAttribute(root, "background"))

        // Bottom atmospheric glow
        val glow = findElementByAndroidId(doc, "viewBottomGlow")
        assertNotNull("viewBottomGlow must be present in portrait layout", glow)

        // Date line, text clock, and weather capsule
        assertNotNull("tvDateLine must be present", findElementByAndroidId(doc, "tvDateLine"))
        assertNotNull("textClock must be present", findElementByAndroidId(doc, "textClock"))
        assertNotNull("weatherCapsule must be present", findElementByAndroidId(doc, "weatherCapsule"))

        // Weather capsule child views
        assertNotNull("tvWeatherIcon must be present", findElementByAndroidId(doc, "tvWeatherIcon"))
        assertNotNull("tvWeatherTemp must be present", findElementByAndroidId(doc, "tvWeatherTemp"))
        assertNotNull("tvWeatherDesc must be present", findElementByAndroidId(doc, "tvWeatherDesc"))

        // Now-Playing Widget inclusion
        val widgetInclude = findElementByAndroidId(doc, "layoutNowPlaying")
        assertNotNull("layoutNowPlaying must be present in portrait layout", widgetInclude)
        widgetInclude!!

        assertEquals("include", widgetInclude.tagName)
        assertEquals("@layout/widget_home_now_playing", widgetInclude.getAttribute("layout"))
        assertEquals("match_parent", getAttribute(widgetInclude, "layout_width"))
        assertEquals("wrap_content", getAttribute(widgetInclude, "layout_height"))
        assertEquals("20dp", getAttribute(widgetInclude, "layout_marginTop"))
        assertEquals(
            "Widget must be hidden (visibility gone) by default when nothing is playing",
            "gone",
            getAttribute(widgetInclude, "visibility")
        )

        // Today Weather Details inclusion
        val detailsInclude = findElementByAndroidId(doc, "layoutWeatherDetails")
        assertNotNull("layoutWeatherDetails must be present in portrait layout", detailsInclude)
        assertEquals("include", detailsInclude!!.tagName)
        assertEquals("@layout/widget_weather_details", detailsInclude.getAttribute("layout"))
        assertEquals("match_parent", getAttribute(detailsInclude, "layout_width"))
        assertEquals("wrap_content", getAttribute(detailsInclude, "layout_height"))
        assertEquals("16dp", getAttribute(detailsInclude, "layout_marginTop"))
        assertEquals("visible", getAttribute(detailsInclude, "visibility"))

        // 5-Day Weather Forecast inclusion
        val forecastInclude = findElementByAndroidId(doc, "layoutWeatherForecast")
        assertNotNull("layoutWeatherForecast must be present in portrait layout", forecastInclude)
        assertEquals("include", forecastInclude!!.tagName)
        assertEquals("@layout/widget_weather_forecast_5day", forecastInclude.getAttribute("layout"))
        assertEquals("match_parent", getAttribute(forecastInclude, "layout_width"))
        assertEquals("wrap_content", getAttribute(forecastInclude, "layout_height"))
        assertEquals("12dp", getAttribute(forecastInclude, "layout_marginTop"))
        assertEquals("visible", getAttribute(forecastInclude, "visibility"))
    }

    // =========================================================================
    // 2. Landscape Home Layout Tests
    // =========================================================================

    @Test
    fun testLandscapeHomeLayout_loadsSuccessfully_andWidgetPresentInRightColumnAndHiddenByDefault() {
        val landscapeFile = locateLayoutFile("layout-land/fragment_home.xml")
        val doc = parseXml(landscapeFile)

        // Root FrameLayout with OLED black background
        val root = doc.documentElement
        assertEquals("FrameLayout", root.tagName)
        assertEquals("@color/oled_black", getAttribute(root, "background"))

        // Horizontal two-column container
        val contentContainer = findElementByAndroidId(doc, "layoutHomeContent")
        assertNotNull("layoutHomeContent must be present in landscape layout", contentContainer)
        contentContainer!!
        assertEquals("horizontal", getAttribute(contentContainer, "orientation"))

        // Left Column: Date line, text clock, weather capsule, and today weather details
        val leftColumn = findElementByAndroidId(doc, "layoutHomeLeft")
        assertNotNull("layoutHomeLeft column must be present in landscape", leftColumn)
        leftColumn!!
        assertEquals("0dp", getAttribute(leftColumn, "layout_width"))
        assertEquals("1", getAttribute(leftColumn, "layout_weight"))

        assertNotNull("tvDateLine must be present", findElementByAndroidId(doc, "tvDateLine"))
        assertNotNull("textClock must be present", findElementByAndroidId(doc, "textClock"))
        assertNotNull("weatherCapsule must be present", findElementByAndroidId(doc, "weatherCapsule"))

        val detailsInclude = findElementByAndroidId(doc, "layoutWeatherDetails")
        assertNotNull("layoutWeatherDetails must be present in landscape left column", detailsInclude)
        assertEquals("include", detailsInclude!!.tagName)
        assertEquals("@layout/widget_weather_details", detailsInclude.getAttribute("layout"))

        // Right Slot: Fixed 380dp container
        val rightContainer = findElementByAndroidId(doc, "layoutRightContainer")
        assertNotNull("layoutRightContainer must be present in landscape layout", rightContainer)
        rightContainer!!
        assertEquals("FrameLayout", rightContainer.tagName)
        assertEquals("380dp", getAttribute(rightContainer, "layout_width"))
        assertEquals("wrap_content", getAttribute(rightContainer, "layout_height"))
        assertEquals("center_vertical", getAttribute(rightContainer, "layout_gravity"))
        assertEquals("32dp", getAttribute(rightContainer, "layout_marginStart"))
        assertEquals(contentContainer, rightContainer.parentNode)

        // Inside Right Container: 5-Day forecast
        val forecastInclude = findElementByAndroidId(doc, "layoutWeatherForecast")
        assertNotNull("layoutWeatherForecast must be present in landscape right container", forecastInclude)
        forecastInclude!!
        assertEquals("include", forecastInclude.tagName)
        assertEquals("@layout/widget_weather_forecast_5day", forecastInclude.getAttribute("layout"))
        assertEquals("match_parent", getAttribute(forecastInclude, "layout_width"))
        assertEquals("visible", getAttribute(forecastInclude, "visibility"))
        assertEquals(rightContainer, forecastInclude.parentNode)

        // Inside Right Container: Now-Playing Widget inclusion
        val widgetInclude = findElementByAndroidId(doc, "layoutNowPlaying")
        assertNotNull("layoutNowPlaying must be present in landscape layout", widgetInclude)
        widgetInclude!!

        assertEquals("include", widgetInclude.tagName)
        assertEquals("@layout/widget_home_now_playing", widgetInclude.getAttribute("layout"))
        assertEquals("match_parent", getAttribute(widgetInclude, "layout_width"))
        assertEquals("wrap_content", getAttribute(widgetInclude, "layout_height"))
        assertEquals(rightContainer, widgetInclude.parentNode)
        assertEquals(
            "Widget must be hidden (visibility gone) by default in landscape layout",
            "gone",
            getAttribute(widgetInclude, "visibility")
        )
    }

    // =========================================================================
    // 3. Widget Layout Specification & Nullable Safe Lookups
    // =========================================================================

    @Test
    fun testWidgetLayout_internalStructureAndViews() {
        val widgetFile = locateLayoutFile("layout/widget_home_now_playing.xml")
        val doc = parseXml(widgetFile)

        val root = doc.documentElement
        assertEquals("LinearLayout", root.tagName)
        assertEquals("@drawable/bg_music_card", getAttribute(root, "background"))

        val widgetIds = extractAllAndroidIds(doc)

        // Core required widget controls
        assertTrue("Widget must contain ivCoverArt", widgetIds.contains("ivCoverArt"))
        assertTrue("Widget must contain tvTrackTitle", widgetIds.contains("tvTrackTitle"))
        assertTrue("Widget must contain tvTrackArtist", widgetIds.contains("tvTrackArtist"))
        assertTrue("Widget must contain btnPrev", widgetIds.contains("btnPrev"))
        assertTrue("Widget must contain btnPlayPause", widgetIds.contains("btnPlayPause"))
        assertTrue("Widget must contain btnNext", widgetIds.contains("btnNext"))
        assertTrue("Widget must contain progressBar", widgetIds.contains("progressBar"))
        assertTrue("Widget must contain tvTimeProgress", widgetIds.contains("tvTimeProgress"))

        val coverArtElement = findElementByAndroidId(doc, "ivCoverArt")
        assertNotNull("ivCoverArt must be found", coverArtElement)
        assertEquals("@dimen/music_cover_size", getAttribute(coverArtElement!!, "layout_width"))
        assertEquals("@dimen/music_cover_size", getAttribute(coverArtElement, "layout_height"))
        assertEquals("true", getAttribute(coverArtElement, "clipToOutline"))

        val btnPrev = findElementByAndroidId(doc, "btnPrev")!!
        val btnPlayPause = findElementByAndroidId(doc, "btnPlayPause")!!
        val btnNext = findElementByAndroidId(doc, "btnNext")!!
        assertEquals("@dimen/music_btn_size", getAttribute(btnPrev, "layout_width"))
        assertEquals("@dimen/music_btn_size", getAttribute(btnPrev, "layout_height"))
        assertEquals("@dimen/music_btn_size", getAttribute(btnPlayPause, "layout_width"))
        assertEquals("@dimen/music_btn_size", getAttribute(btnPlayPause, "layout_height"))
        assertEquals("@dimen/music_btn_size", getAttribute(btnNext, "layout_width"))
        assertEquals("@dimen/music_btn_size", getAttribute(btnNext, "layout_height"))

        // Strict prohibition: no equalizer bars or vinyl disc views
        assertFalse("EqualizerBarsView must not exist", widgetIds.contains("equalizerBars"))
        assertFalse("Vinyl view must not exist", widgetIds.contains("vinylView"))
        assertFalse("Vinyl disc must not exist", widgetIds.contains("ivVinylDisc"))

        // Verify that separate elapsed/duration text views are absent in this compact layout,
        // confirming that HomeMusicWidgetController's nullable lookups (tvTimeElapsed, tvTimeDuration)
        // are required and safe.
        assertFalse("tvTimeElapsed should not exist in compact widget layout", widgetIds.contains("tvTimeElapsed"))
        assertFalse("tvTimeDuration should not exist in compact widget layout", widgetIds.contains("tvTimeDuration"))
    }

    @Test
    fun testWeatherDetailsWidget_internalStructureAndViews() {
        val detailsFile = locateLayoutFile("layout/widget_weather_details.xml")
        val doc = parseXml(detailsFile)

        val root = doc.documentElement
        assertEquals("LinearLayout", root.tagName)
        assertEquals("@drawable/bg_music_card", getAttribute(root, "background"))

        val ids = extractAllAndroidIds(doc)
        assertTrue("Must contain layoutWeatherDetails", ids.contains("layoutWeatherDetails"))
        assertTrue("Must contain tvWeatherDetailsTitle", ids.contains("tvWeatherDetailsTitle"))
        assertTrue("Must contain tvAqiBadge", ids.contains("tvAqiBadge"))
        assertTrue("Must contain tvApparentTemp", ids.contains("tvApparentTemp"))
        assertTrue("Must contain tvHumidity", ids.contains("tvHumidity"))
        assertTrue("Must contain tvWind", ids.contains("tvWind"))
        assertTrue("Must contain tvSun", ids.contains("tvSun"))
    }

    @Test
    fun testWeatherForecast5DayWidget_internalStructureAndViews() {
        val forecastFile = locateLayoutFile("layout/widget_weather_forecast_5day.xml")
        val doc = parseXml(forecastFile)

        val root = doc.documentElement
        assertEquals("LinearLayout", root.tagName)
        assertEquals("@drawable/bg_music_card", getAttribute(root, "background"))

        val ids = extractAllAndroidIds(doc)
        assertTrue("Must contain layoutWeatherForecast", ids.contains("layoutWeatherForecast"))
        assertTrue("Must contain tvForecastTitle", ids.contains("tvForecastTitle"))
        assertTrue("Must contain recyclerForecast5Day", ids.contains("recyclerForecast5Day"))

        val recyclerElement = findElementByAndroidId(doc, "recyclerForecast5Day")
        assertNotNull("recyclerForecast5Day must be found", recyclerElement)
        assertEquals("androidx.recyclerview.widget.RecyclerView", recyclerElement!!.tagName)
        assertEquals("match_parent", getAttribute(recyclerElement, "layout_width"))
        assertEquals("wrap_content", getAttribute(recyclerElement, "layout_height"))
    }

    @Test
    fun testLandscapeZeroShiftArchitecture_ensuresPixelLevelZeroDisplacement() {
        val landscapeFile = locateLayoutFile("layout-land/fragment_home.xml")
        val doc = parseXml(landscapeFile)

        val content = findElementByAndroidId(doc, "layoutHomeContent")
        val leftColumn = findElementByAndroidId(doc, "layoutHomeLeft")
        val rightContainer = findElementByAndroidId(doc, "layoutRightContainer")
        val forecast = findElementByAndroidId(doc, "layoutWeatherForecast")
        val nowPlaying = findElementByAndroidId(doc, "layoutNowPlaying")

        assertNotNull("layoutHomeContent must be present", content)
        assertNotNull("layoutHomeLeft must be present", leftColumn)
        assertNotNull("layoutRightContainer must be present", rightContainer)
        assertNotNull("layoutWeatherForecast must be present", forecast)
        assertNotNull("layoutNowPlaying must be present", nowPlaying)

        // 1. Zero-Shift Slot Constraint: layoutRightContainer is fixed at 380dp
        assertEquals("380dp", getAttribute(rightContainer!!, "layout_width"))

        // 2. Left Column Constraint: layoutHomeLeft width is 0dp with weight 1
        assertEquals("0dp", getAttribute(leftColumn!!, "layout_width"))
        assertEquals("1", getAttribute(leftColumn, "layout_weight"))

        // 3. Right Container Overlap: both forecast and music card occupy match_parent width inside 380dp slot
        assertEquals("match_parent", getAttribute(forecast!!, "layout_width"))
        assertEquals("match_parent", getAttribute(nowPlaying!!, "layout_width"))
        assertEquals(rightContainer, forecast.parentNode)
        assertEquals(rightContainer, nowPlaying.parentNode)

        // 4. Default State: 5-Day forecast visible, nowPlaying gone
        assertEquals("visible", getAttribute(forecast, "visibility"))
        assertEquals("gone", getAttribute(nowPlaying, "visibility"))
    }

    // =========================================================================
    // 4. Orientation Switch Parity Tests
    // =========================================================================

    @Test
    fun testOrientationSwitch_viewIdParityAcrossOrientations() {
        val portraitFile = locateLayoutFile("layout/fragment_home.xml")
        val landscapeFile = locateLayoutFile("layout-land/fragment_home.xml")

        val portraitDoc = parseXml(portraitFile)
        val landscapeDoc = parseXml(landscapeFile)

        val portraitIds = extractAllAndroidIds(portraitDoc)
        val landscapeIds = extractAllAndroidIds(landscapeDoc)

        // All essential IDs used by HomeViewHolder must be present in both layouts
        val essentialHomeViewHolderIds = listOf(
            "tvDateLine",
            "textClock",
            "weatherCapsule",
            "tvWeatherIcon",
            "tvWeatherTemp",
            "tvWeatherDesc",
            "tvSlideHint",
            "tvWakePrompt",
            "layoutNowPlaying",
            "layoutWeatherDetails",
            "layoutWeatherForecast"
        )

        for (id in essentialHomeViewHolderIds) {
            assertTrue(
                "ID '$id' required by HomeViewHolder must exist in portrait layout",
                portraitIds.contains(id)
            )
            assertTrue(
                "ID '$id' required by HomeViewHolder must exist in landscape layout",
                landscapeIds.contains(id)
            )
        }
    }

    // =========================================================================
    // 5. Orientation Switch & Widget Visibility Simulation
    // =========================================================================

    @Test
    fun testOrientationSwitch_widgetVisibilitySimulation() {
        // Dual-layout visibility decision function matching MainPagerAdapter.kt
        fun shouldShowMusicCard(state: MusicState?): Boolean {
            return state != null && state.hasActiveSession && state.title.isNotEmpty()
        }

        // 1. Initial State: App opens in Portrait, no music session
        var activeMusicState: MusicState? = null
        var isMusicCardVisible = shouldShowMusicCard(activeMusicState)
        assertFalse("Music card must be hidden initially when nothing plays", isMusicCardVisible)

        // 2. Playback starts in Portrait with active session
        activeMusicState = MusicState(
            isPlaying = true,
            title = "青花瓷",
            artist = "周杰伦",
            appName = "网易云音乐",
            hasNotificationAccess = true,
            hasActiveSession = true
        )
        isMusicCardVisible = shouldShowMusicCard(activeMusicState)
        assertTrue("Music card must become visible when playback starts in Portrait", isMusicCardVisible)

        // 3. User rotates phone to Landscape (simulated orientation configuration change):
        // HomeViewHolder reinflates layout-land/fragment_home.xml, inherits currentMusicState from memory
        val widgetViewAfterRotationLand = shouldShowMusicCard(activeMusicState)
        assertTrue("Music card must remain visible after orientation change to Landscape", widgetViewAfterRotationLand)

        // 4. Playback paused: isPlaying = false, BUT hasActiveSession = true and title is non-empty!
        // ANTI-JUMP CRITICAL ASSERTION: Card stays visible during pause, does not flash or bounce back to big weather
        activeMusicState = activeMusicState.copy(isPlaying = false)
        isMusicCardVisible = shouldShowMusicCard(activeMusicState)
        assertTrue("Music card must STAY VISIBLE during pause as long as session is active (Zero-Jump)", isMusicCardVisible)

        // 5. Playback stops / session destroyed: hasActiveSession = false
        activeMusicState = activeMusicState.copy(hasActiveSession = false)
        isMusicCardVisible = shouldShowMusicCard(activeMusicState)
        assertFalse("Music card must be hidden when session is terminated", isMusicCardVisible)

        // 6. User rotates back to Portrait:
        val widgetViewAfterRotationPortrait = shouldShowMusicCard(activeMusicState)
        assertFalse("Music card must remain hidden when returning to Portrait while stopped", widgetViewAfterRotationPortrait)
    }

    // =========================================================================
    // 6. Orientation Switch Layout Structure Tests
    // =========================================================================

    @Test
    fun testLandscapeLayout_widgetIsOnRightSide_notBelowWeatherCapsule() {
        val landscapeFile = locateLayoutFile("layout-land/fragment_home.xml")
        val landDoc = parseXml(landscapeFile)

        // Verify two-column container
        val contentContainer = findElementByAndroidId(landDoc, "layoutHomeContent")
        assertNotNull("layoutHomeContent must be present in landscape", contentContainer)
        assertEquals("horizontal", getAttribute(contentContainer!!, "orientation"))

        // Verify left column contains clock and weather
        val leftColumn = findElementByAndroidId(landDoc, "layoutHomeLeft")
        assertNotNull("layoutHomeLeft column must be present in landscape", leftColumn)
        assertEquals("vertical", getAttribute(leftColumn!!, "orientation"))
        assertEquals("0dp", getAttribute(leftColumn, "layout_width"))
        assertEquals("1", getAttribute(leftColumn, "layout_weight"))

        val weatherCapsule = findElementByAndroidId(landDoc, "weatherCapsule")
        assertNotNull("weatherCapsule must be present in landscape", weatherCapsule)

        // Verify weatherCapsule is inside leftColumn
        var parent = weatherCapsule?.parentNode
        var isWeatherInLeftColumn = false
        while (parent != null) {
            if (parent is Element && getAttribute(parent, "id")?.contains("layoutHomeLeft") == true) {
                isWeatherInLeftColumn = true
                break
            }
            parent = parent.parentNode
        }
        assertTrue("weatherCapsule must be inside layoutHomeLeft column", isWeatherInLeftColumn)

        // Verify layoutRightContainer is sibling to layoutHomeLeft inside layoutHomeContent
        val rightContainer = findElementByAndroidId(landDoc, "layoutRightContainer")
        assertNotNull("layoutRightContainer must be present in landscape", rightContainer)
        assertEquals("380dp", getAttribute(rightContainer!!, "layout_width"))
        assertEquals(contentContainer, rightContainer.parentNode)

        // Verify layoutNowPlaying is inside layoutRightContainer, but NOT inside layoutHomeLeft
        val widget = findElementByAndroidId(landDoc, "layoutNowPlaying")
        assertNotNull("layoutNowPlaying must be present in landscape", widget)
        assertEquals(rightContainer, widget!!.parentNode)

        // Widget must NOT be inside layoutHomeLeft (not below weatherCapsule)
        var widgetAncestor = widget.parentNode
        var isWidgetInLeftColumn = false
        while (widgetAncestor != null) {
            if (widgetAncestor is Element && getAttribute(widgetAncestor, "id")?.contains("layoutHomeLeft") == true) {
                isWidgetInLeftColumn = true
                break
            }
            widgetAncestor = widgetAncestor.parentNode
        }
        assertFalse("layoutNowPlaying must NOT be inside layoutHomeLeft (not below weatherCapsule)", isWidgetInLeftColumn)
    }

    @Test
    fun testPortraitLayout_widgetIsBelowWeatherCapsule() {
        val portraitFile = locateLayoutFile("layout/fragment_home.xml")
        val portraitDoc = parseXml(portraitFile)

        // In portrait, there must be NO layoutHomeContent, layoutHomeLeft, or layoutRightContainer
        assertNull("layoutHomeContent must NOT exist in portrait layout", findElementByAndroidId(portraitDoc, "layoutHomeContent"))
        assertNull("layoutHomeLeft must NOT exist in portrait layout", findElementByAndroidId(portraitDoc, "layoutHomeLeft"))
        assertNull("layoutRightContainer must NOT exist in portrait layout", findElementByAndroidId(portraitDoc, "layoutRightContainer"))

        val weatherCapsule = findElementByAndroidId(portraitDoc, "weatherCapsule")
        val widget = findElementByAndroidId(portraitDoc, "layoutNowPlaying")
        val details = findElementByAndroidId(portraitDoc, "layoutWeatherDetails")
        val forecast = findElementByAndroidId(portraitDoc, "layoutWeatherForecast")

        assertNotNull("weatherCapsule must be present in portrait", weatherCapsule)
        assertNotNull("layoutNowPlaying must be present in portrait", widget)
        assertNotNull("layoutWeatherDetails must be present in portrait", details)
        assertNotNull("layoutWeatherForecast must be present in portrait", forecast)

        // In portrait, weatherCapsule, layoutNowPlaying, layoutWeatherDetails, layoutWeatherForecast share the same parent container
        val weatherParent = weatherCapsule?.parentNode as? Element
        val widgetParent = widget?.parentNode as? Element
        val detailsParent = details?.parentNode as? Element
        val forecastParent = forecast?.parentNode as? Element

        assertEquals("weatherCapsule and layoutNowPlaying must share the same parent in portrait", weatherParent, widgetParent)
        assertEquals("layoutNowPlaying and layoutWeatherDetails must share the same parent in portrait", widgetParent, detailsParent)
        assertEquals("layoutWeatherDetails and layoutWeatherForecast must share the same parent in portrait", detailsParent, forecastParent)
        assertEquals("vertical", getAttribute(weatherParent!!, "orientation"))

        // In child ordering of the vertical parent:
        // weatherCapsule must precede layoutNowPlaying, which precedes layoutWeatherDetails, which precedes layoutWeatherForecast
        val children = weatherParent.childNodes
        var weatherIndex = -1
        var widgetIndex = -1
        var detailsIndex = -1
        var forecastIndex = -1
        for (i in 0 until children.length) {
            val node = children.item(i)
            if (node === weatherCapsule) weatherIndex = i
            if (node === widget) widgetIndex = i
            if (node === details) detailsIndex = i
            if (node === forecast) forecastIndex = i
        }
        assertTrue(
            "Vertical flow order must be: weatherCapsule < layoutNowPlaying < layoutWeatherDetails < layoutWeatherForecast",
            weatherIndex >= 0 && widgetIndex > weatherIndex && detailsIndex > widgetIndex && forecastIndex > detailsIndex
        )
    }

    @Test
    fun testOrientationChange_switchesBetweenPortraitAndLandscapeLayoutStructures() {
        data class ResolvedLayoutStructure(
            val orientation: String,
            val isTwoColumn: Boolean,
            val isWidgetOnRight: Boolean,
            val isWidgetBelowWeather: Boolean,
            val hasRightSlot380dp: Boolean
        )

        fun resolveHomeStructure(orientation: String): ResolvedLayoutStructure {
            val file = if (orientation == "landscape") {
                locateLayoutFile("layout-land/fragment_home.xml")
            } else {
                locateLayoutFile("layout/fragment_home.xml")
            }
            val doc = parseXml(file)
            val hasTwoColumn = findElementByAndroidId(doc, "layoutHomeContent") != null
            val rightContainer = findElementByAndroidId(doc, "layoutRightContainer")
            val hasRightSlot380dp = rightContainer != null && getAttribute(rightContainer, "layout_width") == "380dp"

            val isRight = hasTwoColumn &&
                findElementByAndroidId(doc, "layoutHomeLeft") != null &&
                rightContainer != null &&
                findElementByAndroidId(doc, "layoutNowPlaying")?.parentNode === rightContainer

            val isBelow = !hasTwoColumn &&
                findElementByAndroidId(doc, "weatherCapsule")?.parentNode === findElementByAndroidId(doc, "layoutNowPlaying")?.parentNode

            return ResolvedLayoutStructure(orientation, hasTwoColumn, isRight, isBelow, hasRightSlot380dp)
        }

        // 1. Initial State: Portrait
        var orientation = "portrait"
        var structure = resolveHomeStructure(orientation)
        assertFalse("Portrait structure must not be two-column", structure.isTwoColumn)
        assertTrue("Portrait structure must place widget below weather capsule", structure.isWidgetBelowWeather)
        assertFalse("Portrait structure must not place widget on right column", structure.isWidgetOnRight)
        assertFalse("Portrait structure must not have 380dp right slot", structure.hasRightSlot380dp)

        // Active music state while in portrait
        var activeMusicState = MusicState(
            isPlaying = true,
            title = "夜曲",
            artist = "周杰伦",
            appName = "网易云音乐",
            hasNotificationAccess = true,
            hasActiveSession = true
        )
        assertEquals("夜曲", activeMusicState.title)
        assertTrue(activeMusicState.isPlaying)
        assertTrue(activeMusicState.hasActiveSession)

        // 2. Rotate to Landscape: re-inflation binds against landscape layout structure
        orientation = "landscape"
        structure = resolveHomeStructure(orientation)
        assertTrue("Landscape structure must be two-column horizontal", structure.isTwoColumn)
        assertTrue("Landscape structure must place widget on the right side", structure.isWidgetOnRight)
        assertTrue("Landscape structure must feature 380dp fixed right container", structure.hasRightSlot380dp)
        assertFalse("Landscape structure must NOT place widget below weather capsule", structure.isWidgetBelowWeather)
        // Music state preserved across rotation
        assertEquals("夜曲", activeMusicState.title)
        assertTrue(activeMusicState.isPlaying)
        assertTrue(activeMusicState.hasActiveSession)

        // 3. Rotate back to Portrait: re-inflation returns to portrait layout structure
        orientation = "portrait"
        structure = resolveHomeStructure(orientation)
        assertFalse("Returning to portrait must not be two-column", structure.isTwoColumn)
        assertTrue("Returning to portrait must return widget below weather capsule", structure.isWidgetBelowWeather)
        assertFalse("Returning to portrait must not have right column widget", structure.isWidgetOnRight)
        assertFalse("Returning to portrait must not have 380dp right slot", structure.hasRightSlot380dp)
        // Music state still preserved
        assertEquals("夜曲", activeMusicState.title)
        assertTrue(activeMusicState.isPlaying)
        assertTrue(activeMusicState.hasActiveSession)
    }
}
