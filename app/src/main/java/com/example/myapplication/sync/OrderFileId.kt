package com.example.myapplication.sync

/**
 * 订单文件 `fileId` 的解析与构造。**全项目唯一允许拼/拆这个字符串的地方。**
 *
 * 格式与服务端 `OrderFileService.fileId(:117-122)` 逐字一致：
 * ```
 * A:<店主用户名>:<yyyy>          进货单，按年一个文件
 * B:<店主用户名>:<yyyy>-<MM>     销售单，按月一个文件（MM 恒两位）
 * ```
 *
 * ## ⚠ 服务端还有第三种 `C:<yyyy>-<MM>`，客户端**同步不到**
 *
 * `completedFileId(:124-126)` 生成的是**完成记录文件**，只有两段、没有用户名。
 * `OrderSyncService:53` 明确只接受 A 与 B：
 * ```java
 * if (!OrderFileService.TYPE_A.equals(type) && !OrderFileService.TYPE_B.equals(type)) { … 拒绝 … }
 * ```
 * 所以 `C` 是服务端内部账本（总部处理 A 类订单时把订单原文追加进去，`adminProcessOrder:466/489`），
 * 客户端既拉不到也不该拉。这里**不提供** C 的构造方法：
 * [Mirror.relativePath][com.example.myapplication.mirror.Mirror] 要求 fileId 恰好三段，
 * 传一个 C 进去会抛异常 —— 那是想要的结果，总比静默建出一个错的镜像路径好。
 *
 * ## 为什么值得单独一个类
 *
 * `fileId` 同时是 Room 主键的一列、镜像路径的一段、`stats_contrib.scope` 的后缀、
 * 以及"这是 A 类还是 B 类"的判据（统计只算 B 类）。手写字符串拼接散在各处的话，
 * 少一个 `%02d` 就会让 `B:alice:2026-9` 与 `B:alice:2026-09` 变成两个不同的文件 ——
 * 而两个都能正常同步，只是数据分成了两半，**没有任何一处会报错**。
 */
object OrderFileId {

    /** 进货单（向丽华金龙）。按年一个文件。 */
    const val TYPE_A = "A"

    /** 销售单（卖出）。按月一个文件。**只有这一类计入营收统计。** */
    const val TYPE_B = "B"

    /** A 类没有月份维度，[Parts.month] 一律填这个值。 */
    const val NO_MONTH = 0

    data class Parts(
        /** [TYPE_A] | [TYPE_B]。 */
        val type: String,
        /** 店主用户名。**不是**当前登录用户 —— 文件归属店主，员工操作的是店铺文件（README §4.2）。 */
        val ownerName: String,
        val year: Int,
        /** 1..12；A 类为 [NO_MONTH]。 */
        val month: Int,
    ) {
        val isB: Boolean get() = type == TYPE_B
        val fileId: String get() = if (isB) ofB(ownerName, year, month) else ofA(ownerName, year)

        /**
         * 发给服务端时用的月份：B 类是 1..12，**A 类必须是 null**。
         *
         * 不能直接把 [month] 塞进请求体：A 类的 [NO_MONTH] 是 0，
         * 而服务端 `OrderSyncRequest.month` 是 `Integer`，传 0 会撞上
         * `OrderSyncService:60` 的 `month < 1 || month > 12` 校验 → 400。
         * 于是"同步某一年的进货单"永远失败，而且失败原因（400 参数有误）
         * 完全指不到"客户端把 0 当月份发出去了"这件事上。
         */
        val monthOrNull: Int? get() = if (isB) month else null
    }

    fun ofA(ownerName: String, year: Int): String {
        checkOwner(ownerName)
        checkYear(year)
        return "$TYPE_A:$ownerName:$year"
    }

    fun ofB(ownerName: String, year: Int, month: Int): String {
        checkOwner(ownerName)
        checkYear(year)
        require(month in 1..12) { "B 类月份必须在 1..12，实际 $month" }
        // 恒两位。服务端用 String.format("B:%s:%d-%02d", …)，差一位就是另一个文件。
        return "%s:%s:%d-%02d".format(TYPE_B, ownerName, year, month)
    }

    /** @throws IllegalArgumentException fileId 不是合法的 A/B 形态。 */
    fun parse(fileId: String): Parts {
        val parts = fileId.split(':')
        require(parts.size == 3) { "非法 fileId（必须恰好三段）: $fileId" }
        val owner = parts[1]
        checkOwner(owner)
        return when (parts[0]) {
            TYPE_A -> {
                val y = parts[2].toIntOrNull() ?: throw IllegalArgumentException("非法 fileId 年份: $fileId")
                checkYear(y)
                Parts(TYPE_A, owner, y, NO_MONTH)
            }
            TYPE_B -> {
                val ym = parts[2].split('-')
                require(ym.size == 2) { "非法 B 类 fileId（年份与月份要用 '-' 分隔）: $fileId" }
                val y = ym[0].toIntOrNull() ?: throw IllegalArgumentException("非法 fileId 年份: $fileId")
                checkYear(y)
                // 必须恰好两位：`2026-9` 与服务端的 `2026-09` 是两个不同的文件，
                // 放过去就会静默把一个月的数据劈成两半。
                require(ym[1].length == 2) { "B 类月份必须两位: $fileId" }
                val m = ym[1].toIntOrNull() ?: throw IllegalArgumentException("非法 fileId 月份: $fileId")
                require(m in 1..12) { "B 类月份必须在 1..12: $fileId" }
                Parts(TYPE_B, owner, y, m)
            }
            else -> throw IllegalArgumentException("未知订单文件类型: ${parts[0]}（fileId=$fileId）")
        }
    }

    /** 这个 fileId 是否计入营收统计。A 类是向丽华金龙进货，不是本店的收入。 */
    fun isRevenueFile(fileId: String): Boolean = fileId.startsWith("$TYPE_B:")

    private fun checkOwner(ownerName: String) {
        require(ownerName.isNotEmpty()) { "fileId 里缺店主用户名" }
        require(!ownerName.contains(':')) { "店主用户名不得含冒号: $ownerName" }
    }

    private fun checkYear(year: Int) {
        // 下界与调度器的硬下界一致（方案 §3.4：createdAt 可能是 0，不早于 2020-01）；
        // 上界只是防手滑，避免拼出 `B:alice:20261-09` 这种永远同步不到的 fileId。
        require(year in 1970..9999) { "年份超出范围: $year" }
    }
}
