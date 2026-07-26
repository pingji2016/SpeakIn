package com.speakin.app.ui.recording

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.speakin.app.R
import com.speakin.app.ui.theme.SpeakInRecording

/**
 * 常住底部栏：文字 / 语音 / 图片 快捷入口 + 实时字幕
 *
 * - 空闲：文字(左) + 蓝色麦克风(中) + 图片(右)
 * - 绿色脉冲麦克风 = 正在收音（点击停止录音）
 * - 录音乐时实时字幕显示在按钮上方
 */
@Composable
fun RecordingBar(
    isRecording: Boolean,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    liveCaption: String = "",
    liveCaptionStableLen: Int = 0,
    isTranscribing: Boolean = false,
    onAddText: () -> Unit = {},
    onAddImage: () -> Unit = {},
    onImportAudio: () -> Unit = {},
    onAddFlow: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition()
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(800),
            repeatMode = RepeatMode.Reverse
        )
    )

    // 麦克风按钮颜色动画
    val micColor by animateColorAsState(
        targetValue = if (isRecording) Color(0xFF22C55E)  // 绿色：正在收音
                      else Color(0xFF3B82F6),               // 蓝色：空闲
        label = "micColor"
    )

    val stopColor = Color(0xFFEF4444)  // 红色停止按钮
    val micGlow = micColor.copy(alpha = pulseAlpha * 0.3f)

    Column(modifier = modifier.fillMaxWidth().navigationBarsPadding()) {
        // ─── 实时字幕卡片 ───
        // 录音中实时显示，转写中也保持最后一条字幕不消失
        AnimatedVisibility(
            visible = (isRecording || isTranscribing) && liveCaption.isNotEmpty(),
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f)
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // 流式指示小点
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(
                                    if (liveCaptionStableLen < liveCaption.length)
                                        SpeakInRecording
                                    else
                                        MaterialTheme.colorScheme.primary
                                )
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.live_preview),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    // 稳定前缀 + 变化后缀分段显示
                    if (liveCaptionStableLen > 0 && liveCaptionStableLen < liveCaption.length) {
                        Text(
                            buildAnnotatedString {
                                withStyle(SpanStyle(
                                    color = MaterialTheme.colorScheme.onSurface
                                )) {
                                    append(liveCaption.substring(0, liveCaptionStableLen))
                                }
                                withStyle(SpanStyle(
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Medium
                                )) {
                                    append(liveCaption.substring(liveCaptionStableLen))
                                }
                            },
                            style = MaterialTheme.typography.bodyLarge
                        )
                    } else {
                        Text(
                            text = liveCaption,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        // ─── 底部控制栏（扁平贴边，无圆角） ───
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
        ) {
            // 顶部分割线（替代卡片阴影）
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(0.5.dp)
                    .align(Alignment.TopCenter)
                    .background(
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                    )
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                if (isRecording) {
                    // 录制中：脉冲麦克风图标 + 居中大停止按钮
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 脉冲麦克风指示器
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(micGlow),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Mic,
                                contentDescription = stringResource(R.string.recording),
                                tint = micColor,
                                modifier = Modifier.size(24.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(10.dp))

                        Text(
                            stringResource(R.string.recording),
                            style = MaterialTheme.typography.bodyMedium,
                            color = micColor,
                            modifier = Modifier.weight(1f)
                        )

                        // 大停止按钮 — 红色圆形
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                                .background(stopColor),
                            contentAlignment = Alignment.Center
                        ) {
                            IconButton(
                                onClick = onStopRecording,
                                modifier = Modifier.size(56.dp)
                            ) {
                                Icon(
                                    Icons.Default.Stop,
                                    contentDescription = stringResource(R.string.stop),
                                    tint = Color.White,
                                    modifier = Modifier.size(32.dp)
                                )
                            }
                        }
                    }
                } else if (isTranscribing) {
                    // 转写中
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        stringResource(R.string.transcribing),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    // 空闲：文字(左) + 导入音频 + 语音(中) + 图片(右)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        // 文字按钮
                        IconButton(
                            onClick = onAddText,
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                Icons.Default.TextFields,
                                contentDescription = stringResource(R.string.add_text),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(26.dp)
                            )
                        }

                        // 导入音频按钮
                        IconButton(
                            onClick = onImportAudio,
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                Icons.Default.Audiotrack,
                                contentDescription = stringResource(R.string.add_audio),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(26.dp)
                            )
                        }

                        // 语音按钮（蓝色圆形麦克风）
                        IconButton(
                            onClick = onStartRecording,
                            modifier = Modifier.size(56.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(CircleShape)
                                    .background(micColor),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.Mic,
                                    contentDescription = stringResource(R.string.start_recording),
                                    tint = Color.White,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                        }

                        // 流式布局按钮
                        IconButton(
                            onClick = onAddFlow,
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                Icons.Default.Dashboard,
                                contentDescription = "Add flow layout",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(26.dp)
                            )
                        }

                        // 图片按钮
                        IconButton(
                            onClick = onAddImage,
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                Icons.Default.Image,
                                contentDescription = stringResource(R.string.add_image),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(26.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
