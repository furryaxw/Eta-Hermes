package io.github.mangi.eta.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.itos.coui.layout.CouiPageDefaults
import com.itos.coui.text.CouiTextKind
import com.itos.coui.text.Text as CouiText
import com.itos.coui.theme.CouiTheme
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.ui.app.LocalUiStyle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val SegmentShape = RoundedCornerShape(percent = 50)

/**
 * 分段控制器样式的单选组：整条轨道只容纳一个选中项，选中段用整块胶囊底色标示。
 *
 * 与开关的区别是选项互斥且必须有一个生效，用于「本机 Agent / Hermes 后端」这类二选一。
 */
@Composable
internal fun EtaSegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelectedIndexChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val style = LocalUiStyle.current
    val trackColor: Color
    val segmentColor: Color
    val selectedTextColor: Color
    val unselectedTextColor: Color
    when (style) {
        AppearanceUiStyle.COUI -> {
            val colors = CouiPageDefaults.themeColors(CouiTheme.isDark)
            trackColor = colors.card
            segmentColor = colors.accent
            selectedTextColor = Color.White
            unselectedTextColor = colors.title
        }

        AppearanceUiStyle.MIUIX -> {
            val scheme = MiuixTheme.colorScheme
            trackColor = scheme.surfaceContainerHigh
            segmentColor = scheme.primary
            selectedTextColor = scheme.onPrimary
            unselectedTextColor = scheme.onSurfaceVariantSummary
        }
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(trackColor, SegmentShape)
            .padding(3.dp)
            .selectableGroup(),
    ) {
        options.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            val source = remember { MutableInteractionSource() }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 34.dp)
                    .background(if (selected) segmentColor else Color.Transparent, SegmentShape)
                    .selectable(
                        selected = selected,
                        interactionSource = source,
                        indication = null,
                        role = Role.RadioButton,
                        onClick = { onSelectedIndexChange(index) },
                    )
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                when (style) {
                    AppearanceUiStyle.COUI -> CouiText(
                        text = label,
                        kind = CouiTextKind.PreferenceTitle,
                        color = if (selected) selectedTextColor else unselectedTextColor,
                        maxLines = 1,
                    )

                    AppearanceUiStyle.MIUIX -> Text(
                        text = label,
                        style = MiuixTheme.textStyles.body2,
                        color = if (selected) selectedTextColor else unselectedTextColor,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
