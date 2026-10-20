package com.example.myapplication.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分词规则的测试。
 *
 * 规则由用户指定，且与 v3 的 `ProductRepository.splitQueryIntoTokens`（L204）逐字一致：
 * **连续数字算一个词、其他每个字符各算一个词、空格不算词**。
 * 索引侧与查询侧必须用同一个分词器，否则索引会"看起来建好了但搜不到"，而且不报错。
 */
class ProductTokenizerTest {

    @Test
    fun `连续数字是一个词_其他字符各是一个词`() {
        // 文档里的例子
        assertEquals(
            listOf("苹", "果", "13", "手", "机"),
            ProductTokenizer.tokenize("苹果13手机")
        )
    }

    @Test
    fun `空格不算词`() {
        assertEquals(listOf("可", "乐"), ProductTokenizer.tokenize("可 乐"))
        assertEquals(listOf("可", "乐"), ProductTokenizer.tokenize("  可乐  "))
        assertEquals(listOf("a", "12", "b"), ProductTokenizer.tokenize("a 12 b"))
    }

    @Test
    fun `多段数字各自成词`() {
        // "330" 与 "12" 各自是**一个**词（连续数字），不是拆成单个数字
        assertEquals(
            listOf("可", "口", "可", "乐", "330", "m", "l", "12"),
            ProductTokenizer.tokenize("可口可乐330ml 12")
        )
    }

    @Test
    fun `位置是字符位置_用于相邻加分`() {
        val tp = ProductTokenizer.tokenizeWithPositions("苹果13手机")
        assertEquals(listOf("苹" to 0, "果" to 1, "13" to 2, "手" to 4, "机" to 5), tp)
    }

    @Test
    fun `重复字符各占一个位置`() {
        val tp = ProductTokenizer.tokenizeWithPositions("可口可乐")
        assertEquals(listOf("可" to 0, "口" to 1, "可" to 2, "乐" to 3), tp)
    }

    @Test
    fun `查询词去重但保留顺序`() {
        // 「可口可乐」→ 可,口,可,乐 → 去重后 可,口,乐
        assertEquals(listOf("可", "口", "乐"), ProductTokenizer.tokenizeQuery("可口可乐"))
        // "1122" 是连续数字 → 一个词，不会被拆成 1,1,2,2
        assertEquals(listOf("1122"), ProductTokenizer.tokenizeQuery("1122"))
        // 去重只对完全相同的 token 生效
        assertEquals(listOf("可", "口", "3", "乐"), ProductTokenizer.tokenizeQuery("可口3可乐"))
    }

    @Test
    fun `空串与纯空格产出空列表`() {
        assertEquals(emptyList<String>(), ProductTokenizer.tokenize(""))
        assertEquals(emptyList<String>(), ProductTokenizer.tokenize("   "))
        assertEquals(emptyList<String>(), ProductTokenizer.tokenizeQuery("  "))
    }

    @Test
    fun `全角数字也算数字_连续成一个词`() {
        // Character.isDigit 对全角数字 '１'(U+FF11) 返回 true，
        // 所以全角数字串会被当成一个词。这是**有意**与 v3 行为一致（v3 也用 isDigit）。
        val t = ProductTokenizer.tokenize("可乐１２")
        assertEquals(listOf("可", "乐", "１２"), t)
    }

    // ==================== 关键等价性：数字边界规则不需要正则 ====================

    /**
     * v3 对数字 token 用正则 `(?<!\d)N(?!\d)` 确保前后不是数字。
     * 按本规则分词后**这条正则是多余的**：数字串在索引里就是一个完整的词，
     * 查一个更短的数字串根本不会命中。
     *
     * 这条测试把等价性钉死。它成立才意味着倒排索引可以用等值查询、不必在 SQL 里跑正则
     * （SQLite 也没有原生正则）。
     */
    @Test
    fun `数字_token_的边界正则与索引等值查询等价`() {
        val names = listOf(
            "可口可乐330ml", "可乐", "百事可乐2L装", "3M口罩", "iPhone15Pro",
            "云南白药气雾剂12g", "0号柴油", "100", "1000", "A1B2C3", "12ab34",
            "矿泉水550ml*24瓶", "红牛250ml", "1", "01", "0",
        )
        val queries = listOf(
            "3", "33", "330", "30", "0", "1", "12", "100", "1000", "15", "2", "24", "250", "550",
        )
        var checked = 0
        for (name in names) {
            val tokens = ProductTokenizer.tokenize(name).toSet()
            for (q in queries) {
                val byRegex = Regex("(?<!\\d)$q(?!\\d)").containsMatchIn(name)
                val byIndex = q in tokens
                assertEquals(
                    "名字「$name」查数字「$q」：正则=$byRegex 索引=$byIndex，两者必须一致",
                    byRegex, byIndex
                )
                checked++
            }
        }
        assertTrue("应当覆盖足够多的组合，实际 $checked", checked >= 200)
    }

    @Test
    fun `具体案例_查_33_不匹配_330ml`() {
        // 正则 (?<!\d)33(?!\d) 对「可口可乐330ml」不匹配（33 后面是数字 0）
        assertFalse(Regex("(?<!\\d)33(?!\\d)").containsMatchIn("可口可乐330ml"))
        // 索引里只有 token "330"，查 "33" 同样不命中
        assertFalse("33" in ProductTokenizer.tokenize("可口可乐330ml"))
        // 而查 330 两边都命中
        assertTrue(Regex("(?<!\\d)330(?!\\d)").containsMatchIn("可口可乐330ml"))
        assertTrue("330" in ProductTokenizer.tokenize("可口可乐330ml"))
    }

    @Test
    fun `具体案例_前导零的条码不会被短查询命中`() {
        // 旧数据里有 "0690..." 这种条码；查 "690" 不该命中它
        // （正则 (?<!\d)690(?!\d) 对 "0690" 也不匹配，因为 690 前面是数字 0）
        assertFalse(Regex("(?<!\\d)690(?!\\d)").containsMatchIn("0690123"))
        assertFalse("690" in ProductTokenizer.tokenize("0690123"))
        assertTrue("0690123" in ProductTokenizer.tokenize("0690123"))
    }

    // ==================== 非数字 token ====================

    @Test
    fun `非数字_token_是单字符包含匹配_与索引等值查询等价`() {
        // v3 对非数字 token 用 name.contains(token)，而 token 就是单个字符，
        // 所以 contains ⟺ 该字符在索引里
        val names = listOf("可口可乐330ml", "百事可乐", "雪碧", "A&B<C>", "可翻乐")
        val queries = listOf("可", "乐", "口", "百", "A", "&", "<", "翻", "事")
        for (name in names) {
            val tokens = ProductTokenizer.tokenize(name).toSet()
            for (q in queries) {
                assertEquals(
                    "名字「$name」查字符「$q」",
                    name.contains(q), q in tokens
                )
            }
        }
    }
}
