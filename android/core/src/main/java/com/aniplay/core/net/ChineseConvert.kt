package com.aniplay.core.net

import com.aniplay.core.App

/**
 * 繁體 → 簡體（逐字）。網站資料都是簡體，搜尋時先轉換，打繁體也找得到。
 * 對照表 assets/t2s.txt 由 OpenCC TSCharacters.txt 產生（Apache License 2.0），兩個字一組：繁、簡。
 */
object ChineseConvert {
    private val map: Map<Int, Int> by lazy {
        val text = runCatching {
            App.instance.assets.open("t2s.txt").bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.getOrDefault("")
        val cps = text.codePoints().toArray()
        HashMap<Int, Int>(cps.size).apply {
            var i = 0
            while (i + 1 < cps.size) {
                put(cps[i], cps[i + 1])
                i += 2
            }
        }
    }

    fun toSimplified(s: String): String {
        if (s.isEmpty()) return s
        val sb = StringBuilder(s.length)
        s.codePoints().forEach { sb.appendCodePoint(map[it] ?: it) }
        return sb.toString()
    }
}
