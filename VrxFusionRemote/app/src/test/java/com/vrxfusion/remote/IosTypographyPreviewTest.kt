package com.vrxfusion.remote

import android.graphics.Bitmap
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.core.view.drawToBitmap
import com.vrxfusion.remote.ui.FlightHomeScreen
import com.vrxfusion.remote.ui.theme.VrxTheme
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w360dp-h780dp-port-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class IosTypographyPreviewTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun captureSameHomeWithSystemFontAndInter() {
        val useInter = mutableStateOf(false)
        rule.setContent { VrxTheme(useReviewInter = useInter.value) { FlightHomeScreen({}, {}, {}) } }
        rule.onNodeWithText("旋翼飞行控制台").assertIsDisplayed()
        val system = capture("home-system")
        rule.runOnIdle { useInter.value = true }
        rule.onNodeWithText("旋翼飞行控制台").assertIsDisplayed()
        val inter = capture("home-inter")
        assertFalse("同一界面的拉丁字形应有差异", system.sameAs(inter))
    }

    private fun capture(name: String): Bitmap {
        val file = File("build/typography-preview/$name.png")
        file.parentFile?.mkdirs()
        val bitmap = rule.runOnIdle {
            rule.activity.findViewById<View>(android.R.id.content).drawToBitmap(Bitmap.Config.ARGB_8888)
        }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return bitmap
    }
}
