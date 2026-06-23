package com.speakin.app.domain.asr

/**
 * 渐进式识别结果纠错引擎。
 *
 * 通过对比连续两次周期识别的结果，利用最长公共前缀（LCP）算法
 * 识别"已稳定"的文字前缀和"可能变化"的新增后缀。
 *
 * 场景覆盖：
 * - 追加：前次 "今天天气" → 本次 "今天天气很好" → stablePrefix="今天天气", newSuffix="很好"
 * - 纠正：前次 "金色" → 本次 "今天天气" → stablePrefix="", newSuffix="今天天气"（全部重新开始）
 * - 稳定：前次 "今天天气很好" → 本次 "今天天气很好" → isStable=true
 * - 幻觉消除：前次 "今天。今天" → 本次 "今天天气" → stablePrefix="今天", newSuffix="天气"
 */
class ResultRefiner {

    private var previousText: String = ""
    private var cumulativeStableLen: Int = 0

    /**
     * 对新的识别结果进行渐进式纠错。
     *
     * @param currentText 本次周期识别的完整文本
     * @return [Result] 包含稳定前缀长度和是否已稳定
     */
    fun refine(currentText: String): AsrEngine.StreamingResult {
        if (previousText.isEmpty()) {
            previousText = currentText
            return AsrEngine.StreamingResult(
                text = currentText,
                stableLen = 0,
                isStable = false
            )
        }

        // 计算与上次结果的最长公共前缀
        val lcpLen = longestCommonPrefix(previousText, currentText)

        // 累积稳定长度：取历史上最长的 LCP（一旦稳定就不会回退）
        cumulativeStableLen = maxOf(cumulativeStableLen, lcpLen)

        val isStable = previousText == currentText
        previousText = currentText

        return AsrEngine.StreamingResult(
            text = currentText,
            stableLen = cumulativeStableLen,
            isStable = isStable
        )
    }

    /**
     * 重置状态（新录音会话开始时调用）。
     */
    fun reset() {
        previousText = ""
        cumulativeStableLen = 0
    }

    /**
     * 计算两个字符串的最长公共前缀长度（Unicode 字符级别）。
     */
    private fun longestCommonPrefix(a: String, b: String): Int {
        val minLen = minOf(a.length, b.length)
        for (i in 0 until minLen) {
            if (a[i] != b[i]) return i
        }
        return minLen
    }
}
