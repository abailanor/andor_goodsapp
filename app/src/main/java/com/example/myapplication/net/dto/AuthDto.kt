package com.example.myapplication.net.dto

/**
 * 鉴权相关 DTO。字段名与服务端 `model/dto` 下的各个 `Xxx.java` 逐一对齐（**已逐个读过源码，不是猜的**）。
 *
 * ⚠ 注释里别写 `dto/` 紧跟星号再加 `.java` 这种通配路径：Kotlin 的块注释**支持嵌套**，
 * 那个"斜杠+星号"会开一层新注释、把后面整个文件吞掉，
 * 而报错指向的却是文件末尾的 "Unclosed comment"，很难找。
 *
 * ## ⚠ `priceVersion` / `stockVersion` 不是可选的
 *
 * `LoginRequest` 里它们是原始 `int`。登录响应**只有在你带了游标时才回增量**
 * （`AuthService:95`/`:130` 才 `setSyncData`），漏传 = 每次登录都拿 FULL。
 * 首次登录传 0。
 */

/** `POST /api/auth/login`（店主/管理员）与 `POST /api/auth/login/employee`（员工）共用这两个字段。 */
class LoginRequestDto(
    val username: String,
    val password: String,
    val priceVersion: Int,
    val stockVersion: Int,
)

/** 员工登录的请求体字段名**不一样**（`EmployeeLoginRequest`），别复用 [LoginRequestDto]。 */
class EmployeeLoginRequestDto(
    val merchantCode: String,
    val employeeUsername: String,
    val password: String,
    val priceVersion: Int,
    val stockVersion: Int,
)

/** `AdminLoginRequest`：**没有** version 游标字段（`/api/admin/login` 不回 syncData）。 */
class AdminLoginRequestDto(
    val username: String,
    val password: String,
)

/**
 * `LoginResponse`（商户端登录，店主 / 员工 / ADMIN 都走这个）。
 *
 * 字段的坑：
 *  - `level` = `OWNER` / `EMPLOYEE` / `ADMIN`。**它决定镜像路径的归属者**，
 *    员工操作的是**店主的**文件，所以 `ownerName` 不能拿 `username` 顶。
 *  - `createdAt` = **0 表示未知**（README §4.1）。0 不是"1970 年成立"，
 *    拿它当枚举下界会一路枚举到 1970 打爆服务端，必须有 `epochFloor` + 硬下界。
 *  - `previousLoginAt` = 0 表示从未登录过。与本地存的 `currentLoginAt` 不等
 *    ⇒ **离线期间有别的设备登录过**，必须警告（单会话策略）。
 *  - `syncData` 服务端声明成 `Object`，实际填的是 `SyncDataResponse`；
 *    **登录时没带游标就是 null**。
 */
class LoginResponseDto(
    val token: String?,
    val username: String?,
    val merchantCode: String?,
    val level: String?,
    val syncData: SyncDataResponseDto?,
    val perms: List<String?>?,
    val createdAt: Long?,
    val sessionId: String?,
    val previousLoginAt: Long?,
    val currentLoginAt: Long?,
) {
    /** 权限名集合。按**名字**判断，不按序号 —— 服务端可能插入新权限。 */
    fun permSet(): Set<String> = perms.orEmpty().filterNotNull().toSet()
}

/**
 * `AdminLoginResponse`（总部版 `/api/admin/login`）。
 *
 * ⚠ **没有 `level`，也没有 `syncData`**。`merchantCode` 字段名与商户端一致
 * （源码注释明说了"客户端不必为两种登录写两套解析"），但缺的那两个字段不能假设存在。
 * 需要 `level` 时客户端自己填 [ADMIN_LEVEL]。
 */
class AdminLoginResponseDto(
    val token: String?,
    val username: String?,
    val merchantCode: String?,
    val perms: List<String?>?,
    val createdAt: Long?,
    val sessionId: String?,
    val previousLoginAt: Long?,
    val currentLoginAt: Long?,
) {
    fun permSet(): Set<String> = perms.orEmpty().filterNotNull().toSet()

    companion object {
        /** 总部登录响应里没有 level，但 `Credentials.level` 需要一个值。 */
        const val ADMIN_LEVEL = "ADMIN"
    }
}

/** `POST /api/auth/register`。总部版审批用；`address` 可空。 */
class RegisterRequestDto(
    val username: String,
    val password: String,
    val address: String?,
)

/** `PUT /api/user/password`（商户端，店主与员工共用）。改完**自己的 token 也失效了**，必须重登。 */
class ChangePasswordRequestDto(
    val oldPassword: String,
    val newPassword: String,
)

/** `PUT /api/admin/account`（总部版）。与商户端改密码的字段名**完全不同**。 */
class AdminAccountUpdateRequestDto(
    val currentPassword: String,
    val newUsername: String?,
    val newPassword: String?,
)

/** `PUT /api/employees/me/permissions` 与 `/{username}/permissions`。 */
class PermissionsRequestDto(val perms: List<String>)

/** `POST /api/employees`。`level` 是 `A` / `B`（**不是** OWNER/EMPLOYEE），`perms` 可空。 */
class EmployeeManageRequestDto(
    val username: String,
    val password: String,
    val level: String,
    val perms: List<String>?,
)

/**
 * `GET /api/employees` 的元素（`model/entity/EmployeeInfo`）。
 *
 * 服务端那个类里还有 `passwordHash`，但 getter 上有 `@JsonIgnore`（已核实），
 * **不会**出现在线上响应里，所以这里不声明它。
 */
class EmployeeInfoDto(
    val username: String?,
    val level: String?,
    val perms: List<String?>?,
)

/**
 * 待审批注册（`GET /api/admin/pending-registrations`，总部版）。
 *
 * ⚠ 服务端声明的是 `List<Map<String,Object>>`，值来自 Redis hash（`RedisDataService:469-474`），
 * 所以**全部是字符串**——包括 `createdAt`。别按 `Long` 解析。
 * `passwordHash` 已在服务端显式 remove（`:497`），不会出现在响应里。
 */
class PendingRegistrationDto(
    val username: String?,
    val address: String?,
    val ip: String?,
    val createdAt: String?,
) {
    /** 提交时间。解析不出来返回 null（调用方显示"未知"），**不许**当成 0 —— 0 会排到列表最前面。 */
    fun createdAtMillis(): Long? = createdAt?.toLongOrNull()
}
