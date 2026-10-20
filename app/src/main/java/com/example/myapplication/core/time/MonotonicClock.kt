package com.example.myapplication.core.time

/**
 * 单调时钟。**所有"间隔/空闲时长"的计算都必须走这里。**
 *
 * ## 为什么不能用 System.currentTimeMillis()
 *
 * 墙钟会被 NTP 校时、时区切换、用户手动改时间跳变。用它算间隔可以得到**负数**或**几小时**：
 *
 *  - 算出负数 → "空闲时长 < 阈值"永远成立 → 死连接永远不被发现；
 *  - 算出几小时 → 刚连上的连接立刻被判定超时 → 重连风暴。
 *
 * SSE 的空闲判定（"距上一条任意消息 > 3×心跳间隔即判离线"）是整个重连策略的地基，
 * 而它是唯一一个能检测半开连接的手段——服务端那边的写失败探针要等 TCP 重传超时才生效，
 * 最长可达十几分钟（README §9）。地基用墙钟等于没有。
 *
 * `SystemClock.elapsedRealtime()` 包含深睡眠期间的时间，正是我们要的：
 * 设备睡了 10 分钟，连接大概率已经断了，必须重连。
 * （`uptimeMillis()` 不含深睡眠，会漏判。）
 *
 * ## 与业务时间戳的区分
 *
 * 订单里的 `content.ts`、通知里显示的"几点几分"、日志行的时间——那些是**给人看的墙钟**，
 * 该用 `System.currentTimeMillis()`。这个类只管"过了多久"。
 */
interface MonotonicClock {
    /** 单调递增的毫秒数，含深睡眠。只用于做差，绝对值无意义。 */
    fun elapsedRealtime(): Long
}

/** 生产实现。放在 main 源集但只在 Android 上可用；JVM 单测用 [FakeMonotonicClock]。 */
class AndroidMonotonicClock : MonotonicClock {
    override fun elapsedRealtime(): Long = android.os.SystemClock.elapsedRealtime()
}

/** JVM 单测用。手动推进，不依赖真实时间，测试才可重现。 */
class FakeMonotonicClock(private var now: Long = 0L) : MonotonicClock {
    override fun elapsedRealtime(): Long = now
    fun advance(millis: Long) {
        require(millis >= 0) { "时间不能倒流" }
        now += millis
    }
}
