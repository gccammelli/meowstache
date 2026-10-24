package io.github.gccammelli.meowstache

import android.graphics.Bitmap
import android.graphics.Canvas
import io.ktor.client.engine.mock.respond
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import io.github.gccammelli.meowstache.ui.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UiKitTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private fun draw() = compose.runOnIdle {
        android.view.inspector.WindowInspector.getGlobalWindowViews().forEach { view ->
            if(view.width>0 && view.height>0) view.draw(Canvas(Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)))
        }
    }

    @Test fun chartsHandleEmptySingleAndMissingReadingsWithoutInventingSelections() {
        var data by mutableStateOf<List<Double?>>(emptyList())
        var kind by mutableStateOf(ChartKind.LINE)
        val tooltips=mutableListOf<Double>()
        compose.setContent { AppTheme {
            key(kind) { Chart(data,data.indices.map { "Day $it" },kind,Modifier.testTag("chart"),marks=listOf(1,-1,0,1),
                tooltip={ tooltips+=it; "Reading $it" }) }
        } }
        compose.onNodeWithTag("chart").performTouchInput { click() }; draw()
        assertTrue(tooltips.isEmpty())
        compose.runOnIdle { data=listOf(2.5) }; draw()
        assertEquals(2.5,tooltips.last())
        for (type in ChartKind.entries) {
            compose.runOnIdle { kind=type; data=listOf(0.0,null,3.5) }; draw()
            if(type!=ChartKind.AREA) assertEquals(3.5,tooltips.last())
            val before=tooltips.size
            compose.onNodeWithTag("chart").performTouchInput { click(Offset(width*.12f,height*.5f)) }; draw()
            if(type!=ChartKind.AREA) assertEquals(0.0,tooltips.last()) else assertEquals(before,tooltips.size)
        }
    }

    @Test fun chartDefaultsAndLargeRangesRemainRenderable() {
        compose.setContent { AppTheme {
            Column {
                Chart(listOf(0.0,0.0),listOf("A","B"),ChartKind.BARS)
                Chart(listOf(null,null),listOf("A","B"),ChartKind.DOTS)
                Chart(listOf(1_000.0,100_000.0),listOf("A","B"),ChartKind.LINE,average=50_000.0)
                Chart(listOf(2.5),listOf("One"),ChartKind.DOTS)
            }
        } }
        draw()
        compose.onRoot().assertExists()
    }

    @Test fun disabledSegmentsDoNotChangeSelectionAndEnabledSegmentsDo() {
        var enabled by mutableStateOf(false)
        var dark by mutableStateOf(false)
        var selected by mutableIntStateOf(0)
        compose.setContent { AppTheme {
            Segmented(listOf("First","Second"),selected,{ selected=it },enabled=enabled,dark=dark)
        } }
        compose.onNodeWithText("Second").assertIsNotEnabled().performClick()
        assertEquals(0,selected)
        compose.runOnIdle { enabled=true }
        compose.onNodeWithText("Second").performClick(); assertEquals(1,selected)
        compose.runOnIdle { dark=true; enabled=false }; draw()
        compose.onNodeWithText("First").assertIsNotEnabled().performClick(); assertEquals(1,selected)
        compose.runOnIdle { enabled=true }; draw()
        compose.onNodeWithText("First").performClick(); assertEquals(0,selected)
    }

    @Test fun multipleChoicesAreCommittedOnlyOnSaveAndCanBeDeselected() {
        var selected: Set<String>?=null
        var dismissed=false
        compose.setContent { AppTheme {
            ChoiceDialog("Topics",listOf("Visits","Errors"),setOf("Visits"),{ dismissed=true },multi=true,
                note="Choose what to show",onPick={ selected=it })
        } }
        compose.onNodeWithText("Visits").performClick()
        compose.onNodeWithText("Errors").performClick()
        assertNull(selected); assertFalse(dismissed)
        compose.onNodeWithText("Save").performClick()
        assertEquals(setOf("Errors"),selected); assertTrue(dismissed)
    }

    @Test @Config(qualifiers="w411dp-h891dp-night")
    fun systemNightModeUsesDarkColorsForChartsAndControls() {
        compose.setContent { AppTheme {
            Column {
                Segmented(listOf("First","Second"),0,{},dark=true)
                Chart(listOf(1.0),listOf("Today"),ChartKind.LINE)
                Legend("Visits" to W.Teal,"Average" to W.Purple,dashedLast=false)
            }
        } }
        draw(); assertTrue(W.dark)
        compose.onNodeWithText("Average").assertExists()
        compose.onNodeWithText("First").assertIsSelected()
    }

    @Test fun loadedAvatarsAreReusedAndThePhotoCacheStaysBounded() {
        val pictures=listOf("mochi","luna").map { java.io.File("src/commonMain/composeResources/files/demo/$it.jpg").readBytes() }
        val calls=mutableListOf<String>()
        var url by mutableStateOf("https://photos.example/0")
        val apiField=Repo::class.java.getDeclaredField("api").apply { isAccessible=true }
        val original=apiField.get(Repo)
        @Suppress("UNCHECKED_CAST")
        val cache=Class.forName("io.github.gccammelli.meowstache.ui.KitKt").getDeclaredField("imageCache").apply { isAccessible=true }.get(null) as MutableMap<String,Any>
        cache.clear()
        val storage=androidStorage(androidx.test.core.app.ApplicationProvider.getApplicationContext())
        io.ktor.client.HttpClient(io.ktor.client.engine.mock.MockEngine { request ->
            calls+=request.url.toString()
            respond(pictures[if(request.url.encodedPath=="/0") 0 else 1])
        }).use { http ->
            apiField.set(Repo,Api(storage.auth,storage.secrets,http))
            try {
                compose.setContent { AppTheme { NetImage(url,Modifier.size(240.dp)) } }
                compose.waitUntil(5000) { compose.waitForIdle(); cache.containsKey(url) }
                for(i in 1..24) {
                    compose.runOnIdle { url="https://photos.example/$i" }
                    try { compose.waitUntil(5000) { compose.waitForIdle(); cache.containsKey(url) } }
                    finally {
                        if(i==1) System.getenv("MEOWSTACHE_AVATAR_EVIDENCE")?.let { path -> compose.runOnIdle {
                            val view=compose.activity.window.decorView
                            val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
                            view.draw(Canvas(bitmap))
                            java.io.File(path).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
                        } }
                    }
                }
                assertEquals(24,cache.size); assertFalse(cache.containsKey("https://photos.example/0"))
                compose.runOnIdle { url="https://photos.example/1" }
                compose.waitForIdle(); draw()
                assertEquals(1,calls.count { it=="https://photos.example/1" })
                compose.runOnIdle { url="https://photos.example/0" }
                compose.waitUntil(5000) { compose.waitForIdle(); calls.count { it==url }==2 && cache.containsKey(url) }
            } finally { apiField.set(Repo,original); cache.clear() }
        }
    }
}
