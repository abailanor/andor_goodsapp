package com.example.myapplication.net

import com.example.myapplication.net.dto.BatchUpsertResultDto
import com.example.myapplication.net.dto.ProductAddRequestDto
import com.example.myapplication.net.dto.ProductDataDto

/**
 * [com.example.myapplication.sync.ProductSyncEngine] 用到的那三个商品端点。
 *
 * ## 为什么要有这么个窄接口
 *
 * [Service1Api] 是 `final` 的，而项目没引 Mockito —— JVM 单测里没法给它打桩。
 * 引擎的决策分支又多（FULL 的墓碑、INCREMENTAL 的版本空洞、`action=ADD` 缺 name 要补拉、
 * SERVER_BEHIND 必须交给用户二选一），每一条都值得一个测试，而它们**不该**依赖真实 HTTP。
 * 于是引擎只认这个接口，测试里用一个 fake 实现它。
 *
 * ⚠ 只放引擎真正用到的方法。把 `Service1Api` 的三十来个端点全抄进来等于没有抽象 ——
 * fake 要写三十个 `TODO()`，而每加一个端点就要改两处。
 *
 * ## 错误约定（与 [Service1Api] 一致）
 *
 * 失败一律抛 [ApiError] 的子类，**绝不用 null / 空列表表示失败**。
 * 引擎必须能区分"这个条码不存在"（[ApiError.NotFound] → 当成一次删除处理）与
 * "这次没连上"（[ApiError.Network] → 保留现场、下次再试）—— 两者的动作完全相反，
 * 而用返回值表达失败会把它们压成同一个分支。
 */
interface ProductApi {

    /** `GET /api/products/{barcode}`。不存在 → [ApiError.NotFound]。 */
    suspend fun getProduct(barcode: String): ProductDataDto

    /**
     * `POST /api/products/batch-upsert`。**一批最多 100 条**，分批由调用方负责
     * （服务端 `@Size(max=100)`，超了直接 400 而不是截断）。
     *
     * ⚠ 每个元素必须带**全部**字段，尤其 `orderable` —— 服务端
     * `ProductAddRequest.orderable` 是原始 `boolean`，JSON 里漏掉就是 `false`，
     * 商品被静默改成不可订。见 [ProductAddRequestDto]。
     */
    suspend fun batchUpsertProducts(products: List<ProductAddRequestDto>): List<BatchUpsertResultDto>

    /**
     * `DELETE /api/products/{barcode}`。
     *
     * ⚠ **只有店主能成功**：`ProductController:53` 是 `isOwner(ma)` 门控，不看权限位。
     * 员工调 → [ApiError.Forbidden]。所以引擎排队 DELETE 时要逐条推、且要能容忍 403
     * 一直失败（见 [com.example.myapplication.db.ProductPendingChange.OP_DELETE]）。
     */
    suspend fun deleteProduct(barcode: String)
}
