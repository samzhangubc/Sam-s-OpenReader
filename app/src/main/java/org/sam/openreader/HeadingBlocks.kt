package org.sam.openreader

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*

enum class Op(val label: Int) {
    START(R.string.prefix),
    CONTAINS(R.string.contains),
    NUMBER(R.string.number_block),
    ONE_OF(R.string.marker),
    TITLE(R.string.title_block),
    END(R.string.end_block),
    EXCLUDE(R.string.exclude_block),
}

data class Block(val op: Op, val value: String = "")

private const val DIGITS = "零〇一二两兩三四五六七八九壹贰貳叁參肆伍陆陸柒捌玖"
private val units =
    mapOf(
        '十' to 1,
        '拾' to 1,
        '百' to 2,
        '佰' to 2,
        '千' to 3,
        '仟' to 3,
        '万' to 4,
        '萬' to 4,
        '亿' to 8,
        '億' to 8,
    )

fun validChineseNumber(s: String): Boolean {
    if (s.isEmpty() || s.length > 32) return false
    if (s.all { it in DIGITS }) return true
    var lastSmall = 4
    var lastBig = 16
    var previousUnit = false
    var digits = 0
    for ((i, c) in s.withIndex()) {
        val u = units[c]
        if (u == null) {
            if (c !in DIGITS) return false
            if (c !in "零〇" && ++digits > 1) return false
            previousUnit = false
        } else {
            if (i == 0 && u != 1) return false
            if (u >= 4) {
                if (u >= lastBig) return false
                lastBig = u
                lastSmall = 4
            } else {
                if (u >= lastSmall || previousUnit) return false
                lastSmall = u
            }
            previousUnit = true
            digits = 0
        }
    }
    return s.last() !in "零〇"
}

private const val englishSmall =
    "zero|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen|fourteen|fifteen|sixteen|seventeen|eighteen|nineteen|twenty|thirty|forty|fifty|sixty|seventy|eighty|ninety"
private const val englishUnit = "one|two|three|four|five|six|seven|eight|nine"
internal val englishNumberPattern =
    "(?i:(?:" + englishSmall + ")(?:[- ](?:" + englishUnit + "))?(?:\\s+(?:hundred|thousand)(?:\\s+(?:and\\s+)?(?:" + englishSmall + ")(?:[- ](?:" + englishUnit + "))?)?){0,2})"

private val numberToken =
    Regex(
        "[0-9]+(?:\\.[0-9]+){0,5}|[" + DIGITS + units.keys.joinToString("") + "]+(?:[.．][" + DIGITS + units.keys.joinToString("") + "]+){0,5}|" + englishNumberPattern + "|[A-Za-z]+"
    )
private val romanToken = Regex("(?i)(?=[mdclxvi])M{0,3}(CM|CD|D?C{0,3})(XC|XL|L?X{0,3})(IX|IV|V?I{0,3})")
private val englishSmallWords = englishSmall.split('|')

fun validEnglishNumber(s: String): Boolean {
    val parts = s.lowercase().replace('-', ' ').split(Regex("\\s+")).filter(String::isNotEmpty)
    if (parts.isEmpty() || parts.size > 8) return false
    var total = 0
    var value = 0
    var previous = ""
    var hadAnd = false
    for (part in parts) {
        val unit = englishSmallWords.indexOf(part)
        val tens = mapOf("twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50, "sixty" to 60, "seventy" to 70, "eighty" to 80, "ninety" to 90)[part]
        when {
            part == "and" -> {
                if (previous !in setOf("hundred", "thousand")) return false
                hadAnd = true
                previous = "and"
            }
            part == "hundred" -> {
                if (previous != "unit" || value !in 1..9) return false
                value *= 100
                previous = part
            }
            part == "thousand" -> {
                if (previous !in setOf("unit", "hundred", "tens") || value !in 1..999) return false
                total += value * 1000
                value = 0
                previous = part
            }
            unit in 1..19 -> {
                when (previous) {
                    "tens" -> value += unit
                    "hundred", "thousand", "and" -> value += unit
                    "" -> value += unit
                    else -> return false
                }
                previous = "unit"
                hadAnd = false
            }
            unit == 0 -> {
                if (parts.size != 1 || previous.isNotEmpty()) return false
                previous = "zero"
            }
            tens != null -> {
                if (previous !in setOf("", "hundred", "thousand", "and")) return false
                if (previous == "and" && !hadAnd) return false
                if (value % 100 != 0) return false
                value += tens
                previous = "tens"
                hadAnd = false
            }
            else -> return false
        }
    }
    return previous.isNotEmpty() && total + value <= 999_999
}

fun blockMatch(line: String, blocks: List<Block>): Boolean {
    var cursor = 0
    for (block in blocks) {
        val rest = line.substring(cursor)
        when (block.op) {
            Op.START -> {
                if (cursor != 0 || !line.startsWith(block.value, true)) return false
                cursor += block.value.length
            }
            Op.CONTAINS -> if (!line.contains(block.value, true)) return false
            Op.EXCLUDE ->
                if (block.value.split('|').any { it.isNotEmpty() && line.contains(it, true) })
                    return false
            Op.NUMBER -> {
                val token =
                    numberToken.find(rest)?.takeIf { it.range.first == 0 }?.value ?: return false
                val systems = block.value.lowercase().split('|').map { it.trim() }
                val ok =
                    (("decimal" in systems || "0-9" in systems) && token.all { it in '0'..'9' }) ||
                        (("decimal" in systems || "0-9" in systems) && token.matches(Regex("[0-9]+(?:\\.[0-9]+){1,5}"))) ||
                        (("中文" in systems || "chinese" in systems) &&
                            token.split(Regex("[.．]")).all(::validChineseNumber)) ||
                        ("roman" in systems && romanToken.matches(token)) ||
                        (("english" in systems || "words" in systems) && validEnglishNumber(token)) ||
                        systems.any { it.startsWith("=") && it.drop(1).equals(token, true) }
                if (!ok) return false
                cursor += token.length
            }
            Op.ONE_OF -> {
                val token =
                    block.value
                        .split('|')
                        .filter { it.isNotEmpty() }
                        .sortedByDescending { it.length }
                        .firstOrNull { rest.startsWith(it, true) } ?: return false
                cursor += token.length
            }
            Op.TITLE ->
                if (rest.isNotEmpty()) {
                    val separators = block.value.replace("␠", " ").split('|')
                    if (
                        separators.none { rest.startsWith(it) } ||
                            rest.codePointCount(0, rest.length) > 34
                    )
                        return false
                    cursor = line.length
                }
            Op.END -> if (cursor != line.length) return false
        }
    }
    return blocks.any { it.op == Op.START || it.op == Op.NUMBER || it.op == Op.ONE_OF } &&
        cursor == line.length
}

val strictBlocks =
    listOf(
        Block(Op.START, "第"),
        Block(Op.NUMBER, "decimal|中文"),
        Block(Op.ONE_OF, "章|回|节|節"),
        Block(Op.TITLE, "␠|：|:|—"),
        Block(Op.END),
        Block(Op.EXCLUDE, "。|！|？|!|?"),
    )

@Composable
fun BlockEditor(blocks: List<Block>, update: (List<Block>) -> Unit) {
    val names = Op.entries.map { androidx.compose.ui.res.stringResource(it.label) }
    Column {
        Action(R.string.add_blocks) {
            update(if (blocks.isEmpty()) strictBlocks else blocks + Block(Op.CONTAINS))
        }
        blocks.forEachIndexed { i, b ->
            Choice((i + 1).toString(), names[b.op.ordinal], names) { n ->
                update(blocks.toMutableList().also { it[i] = Block(Op.entries[n]) })
            }
            if (b.op != Op.END)
                OutlinedTextField(
                    b.value,
                    { s -> update(blocks.toMutableList().also { it[i] = b.copy(value = s) }) },
                    label = { Text(names[b.op.ordinal]) },
                )
            Row {
                TextButton(onClick = { update(blocks.toMutableList().also { it.removeAt(i) }) }) {
                    Text("−")
                }
                if (i > 0)
                    TextButton(
                        onClick = {
                            update(
                                blocks.toMutableList().also {
                                    val x = it.removeAt(i)
                                    it.add(i - 1, x)
                                }
                            )
                        }
                    ) {
                        Text("↑")
                    }
                if (i < blocks.lastIndex)
                    TextButton(
                        onClick = {
                            update(
                                blocks.toMutableList().also {
                                    val x = it.removeAt(i)
                                    it.add(i + 1, x)
                                }
                            )
                        }
                    ) {
                        Text("↓")
                    }
            }
        }
    }
}
