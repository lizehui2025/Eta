package io.github.mangi.eta.ui.components

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 运行态渲染动画 spinner；静态态（关闭动画回落）渲染全亮网格。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PixelSpinnerLayoutTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun activeSpinnerRendersAndStaticFallbackRenders() {
        // 无限动画在 Robolectric 下不自动推进，避免等待动画停止。
        compose.mainClock.autoAdvance = false
        val active = mutableStateOf(true)
        compose.setContent {
            MiuixTheme {
                PixelSpinner(active = active.value)
            }
        }
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithTag(PixelSpinnerTag).assertExists()

        compose.runOnIdle { active.value = false }
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithTag(PixelSpinnerTag).assertExists()
    }
}
