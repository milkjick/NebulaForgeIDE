package com.nebulaforge.app.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * 分步向导的通用骨架（开发方案 5.3 的布局硬约束）：
 * 「全屏路由页 + 顶部步骤指示器 + 底部导航条」，**禁止弹窗式向导**。
 *
 * 本文件提供两个可复用组件，供 NewProjectWizardScreen（5.3）与
 * OnboardingWizardScreen（1.7）共用，避免两处各写一套指示器导致断点/样式不一致。
 */

/** 顶部步骤指示器：序号圆点 + 连接线，已完成步骤高亮 */
@Composable
fun WizardStepIndicator(
    currentStep: Int,
    totalSteps: Int,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(totalSteps) { index ->
            val done = index < currentStep
            val active = index == currentStep
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            active -> MaterialTheme.colorScheme.primary
                            done -> MaterialTheme.colorScheme.primaryContainer
                            else -> MaterialTheme.colorScheme.surfaceVariant
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "${index + 1}",
                    style = MaterialTheme.typography.labelMedium,
                    textAlign = TextAlign.Center,
                    color = when {
                        active -> MaterialTheme.colorScheme.onPrimary
                        done -> MaterialTheme.colorScheme.onPrimaryContainer
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
            if (index < totalSteps - 1) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(2.dp)
                        .background(
                            if (index < currentStep) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant
                        )
                )
            }
        }
    }
}

/** 底部向导导航条：左侧返回（可选）、右侧下一步 / 完成 */
@Composable
fun WizardBottomBar(
    nextLabel: String,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
    nextEnabled: Boolean = true,
    backLabel: String? = null,
    onBack: (() -> Unit)? = null,
    leading: (@Composable RowScope.() -> Unit)? = null
) {
    Surface(tonalElevation = 3.dp, modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (onBack != null && backLabel != null) {
                TextButton(onClick = onBack) { Text(backLabel) }
            }
            leading?.invoke(this)
            Box(Modifier.weight(1f))
            Button(onClick = onNext, enabled = nextEnabled) { Text(nextLabel) }
        }
    }
}
