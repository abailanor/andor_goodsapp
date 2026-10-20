package com.example.myapplication.mirror

import com.example.myapplication.core.bytes.ByteOps
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 幂等守卫的判定表测试。
 *
 * 每一条都对应一个真实的重复投递场景（HTTP 响应 + SSE 双通道、游标已提交后进程被杀、
 * SSE 断线期间的订单）。判错的后果是镜像里出现同一 uuid 的两行 —— 文件比服务器长，
 * 此后所有偏移静默错位。
 */
class MirrorGuardsTest {

    private val lineLen = 100L

    // ==================== guardAdd ====================

    @Test
    fun `ADD_追加恰落_EOF_判_APPLY`() {
        // 镜像长度 4（只有版本头），这一行应当落在偏移 4
        assertEquals(
            MirrorGuards.Verdict.APPLY,
            MirrorGuards.guardAdd(
                headerVersion = 0, fileLength = 4L, msgVersion = 1,
                lineStart = 4L, lineLength = lineLen
            )
        )
    }

    @Test
    fun `ADD_第二条接在第一条之后_判_APPLY`() {
        assertEquals(
            MirrorGuards.Verdict.APPLY,
            MirrorGuards.guardAdd(1, 4L + lineLen, 2, 4L + lineLen, lineLen)
        )
    }

    @Test
    fun `ADD_版本已到且长度恰好_判_ALREADY_APPLIED`() {
        // 典型场景：文件写成功了、Room 事务还没跑进程就被杀，重启后同一条消息再来一次
        assertEquals(
            MirrorGuards.Verdict.ALREADY_APPLIED,
            MirrorGuards.guardAdd(1, 4L + lineLen, 1, 4L, lineLen)
        )
    }

    @Test
    fun `ADD_版本已到但长度不对_判_DIVERGED`() {
        // 版本到了、内容却不是这一条 —— 说明中间发生过别的事（比如一次 FULL）
        assertEquals(
            MirrorGuards.Verdict.DIVERGED,
            MirrorGuards.guardAdd(5, 200L, 1, 4L, lineLen)
        )
    }

    @Test
    fun `ADD_增量链有空洞_判_DIVERGED`() {
        // 头部是版本 1，消息却说自己是版本 4 —— 中间少了两条
        assertEquals(
            MirrorGuards.Verdict.DIVERGED,
            MirrorGuards.guardAdd(1, 4L + lineLen, 4, 4L + lineLen, lineLen)
        )
    }

    @Test
    fun `ADD_有缺口_判_DIVERGED`() {
        // 应当落在偏移 4，但文件已经有 200 字节了 —— 前面有我们不认识的内容
        assertEquals(
            MirrorGuards.Verdict.DIVERGED,
            MirrorGuards.guardAdd(0, 200L, 1, 4L, lineLen)
        )
    }

    @Test
    fun `ADD_半写状态_内容写了但版本头没写_判_DIVERGED`() {
        // 这正是"先内容后版本头"这个顺序的自愈路径：
        // 进程在两步之间被杀 → 长度已变、版本未变 → DIVERGED → 隔离 + FULL
        assertEquals(
            MirrorGuards.Verdict.DIVERGED,
            MirrorGuards.guardAdd(
                headerVersion = 0, fileLength = 4L + lineLen, msgVersion = 1,
                lineStart = 4L, lineLength = lineLen
            )
        )
    }

    @Test
    fun `ADD_反向半写_版本头写了但内容没写_也判_DIVERGED`() {
        // 我们从不这么写，但要确认守卫也抓得住
        assertEquals(
            MirrorGuards.Verdict.DIVERGED,
            MirrorGuards.guardAdd(
                headerVersion = 1, fileLength = 4L, msgVersion = 1,
                lineStart = 4L, lineLength = lineLen
            )
        )
    }

    // ==================== guardPatch ====================

    private val zero = ByteOps.utf8("0")
    private val one = ByteOps.utf8("1")

    @Test
    fun `PATCH_版本领先且范围合法_判_APPLY`() {
        assertEquals(
            MirrorGuards.Verdict.APPLY,
            MirrorGuards.guardPatch(1, 200L, 2, 100L, zero, one)
        )
    }

    @Test
    fun `PATCH_版本已到且当前字节等于补丁内容_判_ALREADY_APPLIED`() {
        assertEquals(
            MirrorGuards.Verdict.ALREADY_APPLIED,
            MirrorGuards.guardPatch(2, 200L, 2, 100L, zero, zero)
        )
    }

    @Test
    fun `PATCH_版本已到但当前字节不等于补丁内容_判_DIVERGED`() {
        assertEquals(
            MirrorGuards.Verdict.DIVERGED,
            MirrorGuards.guardPatch(2, 200L, 2, 100L, zero, ByteOps.utf8("7"))
        )
    }

    @Test
    fun `PATCH_读不到旧字节_判_DIVERGED`() {
        assertEquals(
            MirrorGuards.Verdict.DIVERGED,
            MirrorGuards.guardPatch(2, 200L, 2, 100L, zero, null)
        )
    }

    @Test
    fun `PATCH_落在版本头内_判_DIVERGED`() {
        for (off in longArrayOf(0L, 1L, 2L, 3L)) {
            assertEquals(
                "偏移 $off 在版本头内",
                MirrorGuards.Verdict.DIVERGED,
                MirrorGuards.guardPatch(1, 200L, 2, off, zero, one)
            )
        }
    }

    @Test
    fun `PATCH_越界_判_DIVERGED`() {
        assertEquals(
            MirrorGuards.Verdict.DIVERGED,
            MirrorGuards.guardPatch(1, 200L, 2, 199L, ByteOps.utf8("00"), ByteOps.utf8("11"))
        )
    }

    @Test
    fun `PATCH_增量链有空洞_判_DIVERGED`() {
        assertEquals(
            MirrorGuards.Verdict.DIVERGED,
            MirrorGuards.guardPatch(1, 200L, 5, 100L, zero, one)
        )
    }

    // ==================== PatchClassifier ====================

    @Test
    fun `分类_1_字节且落在_statusOffset_是状态变更`() {
        assertEquals(
            PatchKind.STATUS,
            PatchClassifier.classify(142L, 1, 142L, null)
        )
    }

    @Test
    fun `分类_150_字节且落在_remarkOffset_是备注变更`() {
        assertEquals(
            PatchKind.REMARK,
            PatchClassifier.classify(200L, 150, 142L, 200L)
        )
    }

    @Test
    fun `分类_两者都不匹配_是_UNKNOWN_不要猜`() {
        assertEquals(
            PatchKind.UNKNOWN,
            PatchClassifier.classify(999L, 1, 142L, 200L)
        )
    }

    @Test
    fun `分类_偏移对但长度不对_是_UNKNOWN`() {
        // 1 字节打在 remarkOffset 上、或 150 字节打在 statusOffset 上，都不该被认出来
        assertEquals(PatchKind.UNKNOWN, PatchClassifier.classify(200L, 1, 142L, 200L))
        assertEquals(PatchKind.UNKNOWN, PatchClassifier.classify(142L, 150, 142L, 200L))
    }

    @Test
    fun `分类_索引里没有该订单_是_UNKNOWN`() {
        assertEquals(PatchKind.UNKNOWN, PatchClassifier.classify(142L, 1, null, null))
    }

    @Test
    fun `分类_B_类订单没有_remarkOffset_150_字节补丁是_UNKNOWN`() {
        assertEquals(PatchKind.UNKNOWN, PatchClassifier.classify(200L, 150, 142L, null))
    }
}
