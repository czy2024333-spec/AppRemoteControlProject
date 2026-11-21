package com.vrxfusion.remote

import android.graphics.Bitmap
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.core.view.drawToBitmap
import com.vrxfusion.remote.ui.*
import com.vrxfusion.remote.ui.theme.VrxTheme
import com.vrxfusion.remote.unified.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="zh-rCN-w360dp-h780dp-port-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UnifiedUiTest {
    @get:Rule val rule=createAndroidComposeRule<ComponentActivity>()
    private val json=Json { ignoreUnknownKeys=true }
    private fun data(name: String)=json.parseToJsonElement(javaClass.classLoader!!.getResource("unified/$name.json")!!.readText()).jsonObject["data"]!!
    private fun state()=json.decodeFromJsonElement<UnifiedSnapshot>(data("state"))
    private fun save(name: String) {
        val file=File("build/cockpit-screenshots/$name.png"); file.parentFile!!.mkdirs()
        val bitmap=rule.runOnIdle { rule.activity.findViewById<View>(android.R.id.content).drawToBitmap(Bitmap.Config.ARGB_8888) }
        file.outputStream().buffered().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
    }
    @Test fun groupFilterOnlyChangesVisibleMembersAndMissingTelemetryStaysMissing() {
        val state=state(); val directory=json.decodeFromJsonElement<UnifiedDirectory>(data("devices"))
        rule.setContent { VrxTheme { Column(Modifier.fillMaxSize().background(Color(0xFF101D2B))
            .verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            UnifiedFleetContent(state,directory,true) { true }
        } } }
        rule.onNodeWithText("alpha").performClick()
        rule.onNodeWithText("sim-drone-1").assertExists(); rule.onNodeWithText("sim-drone-3").assertDoesNotExist()
        rule.onAllNodesWithText("模拟测量 · GNSS未知 · 电量未知").assertCountEquals(2)
        save("unified-fleet-group")
        rule.onNodeWithText("全部成员").performScrollTo().performClick()
        rule.onNodeWithText("sim-drone-3").assertExists()
        rule.onAllNodesWithText("100%").assertCountEquals(0)
    }
    @Test fun invalidStateNeverPrintsZeroCoordinates() {
        val snapshot=state(); val missing=snapshot.members.first().copy(position=VectorMeasurement())
        assertEquals("X —  Y —  Z — m",positionText(missing,true))
        val snapshotOnly=snapshot.copy(members=listOf(missing),groups=emptyMap())
        rule.setContent { VrxTheme { Column(Modifier.fillMaxSize().background(Color(0xFF101D2B)).verticalScroll(rememberScrollState())) {
            UnifiedFleetContent(snapshotOnly,null,false) { false }
        } } }
        rule.onNodeWithText("X —  Y —  Z — m").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("状态过期或无效 · 仅供历史参考").assertExists()
        save("unified-fleet-invalid")
    }
}
