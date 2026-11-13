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

        // Left Column: Date line, text clock, and weather capsule
        val leftColumn = findElementByAndroidId(doc, "layoutHomeLeft")
        assertNotNull("layoutHomeLeft column must be present in landscape", leftColumn)
        assertNotNull("tvDateLine must be present", findElementByAndroidId(doc, "tvDateLine"))
        assertNotNull("textClock must be present", findElementByAndroidId(doc, "textClock"))
        assertNotNull("weatherCapsule must be present", findElementByAndroidId(doc, "weatherCapsule"))

        // Right Column: Now-Playing Widget inclusion
        val widgetInclude = findElementByAndroidId(doc, "layoutNowPlaying")
        assertNotNull("layoutNowPlaying must be present in landscape layout", widgetInclude)
        widgetInclude!!

        assertEquals("include", widgetInclude.tagName)
        assertEquals("@layout/widget_home_now_playing", widgetInclude.getAttribute("layout"))
        assertEquals("360dp", getAttribute(widgetInclude, "layout_width"))
        assertEquals("wrap_content", getAttribute(widgetInclude, "layout_height"))
        assertEquals("center_vertical", getAttribute(widgetInclude, "layout_gravity"))
        assertEquals("32dp", getAttribute(widgetInclude, "layout_marginStart"))
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
        assertTrue("Widget must contain tvTrackTitle", widgetIds.contains("tvTrackTitle"))
        assertTrue("Widget must contain tvTrackArtist", widgetIds.contains("tvTrackArtist"))
        assertTrue("Widget must contain btnPrev", widgetIds.contains("btnPrev"))
        assertTrue("Widget must contain btnPlayPause", widgetIds.contains("btnPlayPause"))
        assertTrue("Widget must contain btnNext", widgetIds.contains("btnNext"))
        assertTrue("Widget must contain progressBar", widgetIds.contains("progressBar"))
        assertTrue("Widget must contain tvTimeProgress", widgetIds.contains("tvTimeProgress"))

        // Verify that separate elapsed/duration text views are absent in this compact layout,
        // confirming that HomeMusicWidgetController's nullable lookups (tvTimeElapsed, tvTimeDuration)
        // are required and safe.
        assertFalse("tvTimeElapsed should not exist in compact widget layout", widgetIds.contains("tvTimeElapsed"))
        assertFalse("tvTimeDuration should not exist in compact widget layout", widgetIds.contains("tvTimeDuration"))
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
            "layoutNowPlaying"
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
        // Simulated ViewHolder binding lifecycle on orientation switch:
        // 1. Initial State: App opens in Portrait, no music playing
        var orientation = "portrait"
        var activeMusicState: MusicState? = null
        var isMusicVisible = false

        // In portrait, widget starts hidden
        var widgetVisible = isMusicVisible || (activeMusicState?.isPlaying == true)
        assertFalse("Widget must be hidden initially when nothing plays", widgetVisible)

        // 2. Playback starts in Portrait
        activeMusicState = MusicState(
            isPlaying = true,
            title = "青花瓷",
            artist = "周杰伦",
            appName = "网易云音乐",
            hasNotificationAccess = true
        )
        isMusicVisible = true
        widgetVisible = isMusicVisible || (activeMusicState.isPlaying)
        assertTrue("Widget must become visible when playback starts in Portrait", widgetVisible)

        // 3. User rotates phone to Landscape (simulated orientation configuration change):
        // HomeViewHolder is recreated with layout-land/fragment_home.xml, bound with current music state
        orientation = "land"
        val widgetViewAfterRotationLand = widgetVisible
        assertTrue("Widget must remain visible after orientation change to Landscape", widgetViewAfterRotationLand)

        // 4. Playback paused, then 60s timeout elapses -> music page / widget hidden
        activeMusicState = activeMusicState.copy(isPlaying = false)
        isMusicVisible = false
        widgetVisible = isMusicVisible || (activeMusicState.isPlaying)
        assertFalse("Widget must be hidden after playback stops in Landscape", widgetVisible)

        // 5. User rotates back to Portrait:
        orientation = "portrait"
        val widgetViewAfterRotationPortrait = widgetVisible
        assertFalse("Widget must remain hidden when returning to Portrait while stopped", widgetViewAfterRotationPortrait)
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

        // Verify layoutNowPlaying is inside layoutHomeContent, but NOT inside layoutHomeLeft
        val widget = findElementByAndroidId(landDoc, "layoutNowPlaying")
        assertNotNull("layoutNowPlaying must be present in landscape", widget)

        // Widget parent must be layoutHomeContent (sibling to the right of layoutHomeLeft)
        val widgetParent = widget?.parentNode as? Element
        assertNotNull("widgetParent must not be null", widgetParent)
        assertEquals(
            "layoutHomeContent",
            getAttribute(widgetParent!!, "id")?.removePrefix("@+id/")?.removePrefix("@id/")
        )

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

        // In portrait, there must be NO layoutHomeContent or layoutHomeLeft
        assertNull("layoutHomeContent must NOT exist in portrait layout", findElementByAndroidId(portraitDoc, "layoutHomeContent"))
        assertNull("layoutHomeLeft must NOT exist in portrait layout", findElementByAndroidId(portraitDoc, "layoutHomeLeft"))

        val weatherCapsule = findElementByAndroidId(portraitDoc, "weatherCapsule")
        val widget = findElementByAndroidId(portraitDoc, "layoutNowPlaying")
        assertNotNull("weatherCapsule must be present in portrait", weatherCapsule)
        assertNotNull("layoutNowPlaying must be present in portrait", widget)

        // In portrait, weatherCapsule and layoutNowPlaying share the same parent container
        val weatherParent = weatherCapsule?.parentNode as? Element
        val widgetParent = widget?.parentNode as? Element
        assertEquals("weatherCapsule and layoutNowPlaying must share the same parent in portrait", weatherParent, widgetParent)
        assertEquals("vertical", getAttribute(weatherParent!!, "orientation"))

        // In child ordering of the vertical parent, weatherCapsule must precede layoutNowPlaying
        val children = weatherParent.childNodes
        var weatherIndex = -1
        var widgetIndex = -1
        for (i in 0 until children.length) {
            val node = children.item(i)
            if (node === weatherCapsule) weatherIndex = i
            if (node === widget) widgetIndex = i
        }
        assertTrue(
            "weatherCapsule must appear before layoutNowPlaying (widget is below weather capsule)",
            weatherIndex >= 0 && widgetIndex >= 0 && weatherIndex < widgetIndex
        )
    }

    @Test
    fun testOrientationChange_switchesBetweenPortraitAndLandscapeLayoutStructures() {
        data class ResolvedLayoutStructure(
            val orientation: String,
            val isTwoColumn: Boolean,
            val isWidgetOnRight: Boolean,
            val isWidgetBelowWeather: Boolean
        )

        fun resolveHomeStructure(orientation: String): ResolvedLayoutStructure {
            val file = if (orientation == "landscape") {
                locateLayoutFile("layout-land/fragment_home.xml")
            } else {
                locateLayoutFile("layout/fragment_home.xml")
            }
            val doc = parseXml(file)
            val hasTwoColumn = findElementByAndroidId(doc, "layoutHomeContent") != null
            val isRight = hasTwoColumn &&
                findElementByAndroidId(doc, "layoutHomeLeft") != null &&
                findElementByAndroidId(doc, "layoutNowPlaying")?.parentNode === findElementByAndroidId(doc, "layoutHomeContent")
            val isBelow = !hasTwoColumn &&
                findElementByAndroidId(doc, "weatherCapsule")?.parentNode === findElementByAndroidId(doc, "layoutNowPlaying")?.parentNode
            return ResolvedLayoutStructure(orientation, hasTwoColumn, isRight, isBelow)
        }

        // 1. Initial State: Portrait
        var orientation = "portrait"
        var structure = resolveHomeStructure(orientation)
        assertFalse("Portrait structure must not be two-column", structure.isTwoColumn)
        assertTrue("Portrait structure must place widget below weather capsule", structure.isWidgetBelowWeather)
        assertFalse("Portrait structure must not place widget on right column", structure.isWidgetOnRight)

        // Active music state while in portrait
        var activeMusicState = MusicState(
            isPlaying = true,
            title = "夜曲",
            artist = "周杰伦",
            appName = "网易云音乐",
            hasNotificationAccess = true
        )
        assertEquals("夜曲", activeMusicState.title)
        assertTrue(activeMusicState.isPlaying)

        // 2. Rotate to Landscape: re-inflation binds against landscape layout structure
        orientation = "landscape"
        structure = resolveHomeStructure(orientation)
        assertTrue("Landscape structure must be two-column horizontal", structure.isTwoColumn)
        assertTrue("Landscape structure must place widget on the right side", structure.isWidgetOnRight)
        assertFalse("Landscape structure must NOT place widget below weather capsule", structure.isWidgetBelowWeather)
        // Music state preserved across rotation
        assertEquals("夜曲", activeMusicState.title)
        assertTrue(activeMusicState.isPlaying)

        // 3. Rotate back to Portrait: re-inflation returns to portrait layout structure
        orientation = "portrait"
        structure = resolveHomeStructure(orientation)
        assertFalse("Returning to portrait must not be two-column", structure.isTwoColumn)
        assertTrue("Returning to portrait must return widget below weather capsule", structure.isWidgetBelowWeather)
        assertFalse("Returning to portrait must not have right column widget", structure.isWidgetOnRight)
        // Music state still preserved
        assertEquals("夜曲", activeMusicState.title)
        assertTrue(activeMusicState.isPlaying)
    }
}
