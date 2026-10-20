package com.example.myapplication.db

/**
 * 商品名分词器。**规则由用户指定，且与现有 `ProductRepository.splitQueryIntoTokens`
 * （L204）逐字一致**，所以搜索行为不会漂移：
 *
 *  1. 连续的数字算**一个**词（`"330"` 是一个词，不是三个）；
 *  2. 其他每个字符**各算一个**词（`"可乐"` → `["可", "乐"]`）；
 *  3. 空格不算词。
 *
 * 例：`"苹果13手机"` → `["苹", "果", "13", "手", "机"]`
 *
 * ## 一个意外的好处：数字边界规则不需要正则
 *
 * 现有代码对数字 token 用 `(?<!\d)N(?!\d)`（`matchesNum`，L188）确保前后不是数字。
 * 按上面的规则分词后这条正则**是多余的**：
 *
 *  - 「可口可乐330ml」只会产出 token `330`；
 *  - 查 `33` → 分词得到 token `33` → 索引里没有这个词 → 不匹配；
 *  - 而正则 `(?<!\d)33(?!\d)` 对「330ml」同样不匹配（33 后面是数字 0）。
 *
 * **两者等价**，所以索引表用等值查询即可，不需要在 SQL 里跑正则。
 *
 * ## 索引侧与查询侧必须用同一个分词器
 *
 * 建索引时按它切商品名，查询时也按它切用户输入。两边规则不一致的话，
 * 索引会"看起来建好了但搜不到"——而且不报错。
 */
object ProductTokenizer {

    /**
     * 切词并带上**字符位置**（不是字节位置——这里只用于"相邻加分"的相对比较，
     * 与协议的字节偏移无关，不要混用）。
     *
     * @return `Pair(token, startPos)` 列表，按出现顺序。重复的 (token,pos) 不会出现，
     *         但同一个 token 可以在不同位置出现多次（例如「可口可乐」里的「可」）。
     */
    fun tokenizeWithPositions(text: String): List<Pair<String, Int>> {
        val out = ArrayList<Pair<String, Int>>()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c.isWhitespace()) {
                // 空格不算词，直接跳过
                i++
                continue
            }
            if (c.isDigit()) {
                val start = i
                while (i < text.length && text[i].isDigit()) i++
                out.add(text.substring(start, i) to start)
            } else {
                out.add(c.toString() to i)
                i++
            }
        }
        return out
    }

    /** 只要词，不要位置。查询侧用这个。 */
    fun tokenize(text: String): List<String> = tokenizeWithPositions(text).map { it.first }

    /**
     * 查询词去重（保持顺序）。
     *
     * 「可口可乐」→ `["可","乐"]`（第二个「可」被去掉）。
     * 不去重的话 `COUNT(DISTINCT token)` 虽然结果一样，但 IN 列表会白白变长。
     */
    fun tokenizeQuery(text: String): List<String> = tokenize(text).distinct()
}
