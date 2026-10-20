package com.example.myapplication.db

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 当前的数据作用域（storeKey = merchantCode）。
 *
 * 离线模式（还没注册/登录）用 [LOCAL_STORE_KEY]；登录成功后换成真实 merchantCode。
 *
 * ## 为什么是一个进程级单例而不是构造参数
 *
 * 现有的 `ProductRepository` / `AIViewModel` / 各 ViewModel 都是在 `MainActivity.onCreate`
 * 里手工装配的（项目没有 DI 框架）。把 storeKey 做成构造参数会要求整条装配链都改签名，
 * 而这一步的目标只是"让数据按店铺隔离"。用 `StateFlow` + `flatMapLatest` 还能让
 * 登录/登出时所有正在观察的 Flow **自动重新查询**，不需要重建 ViewModel。
 *
 * ## 收养
 *
 * 离线期间积累的数据挂在 `__local__` 下。首次登录成功后，"上线向导"会在**一个事务**里
 * 把所有表的 `storeKey` 从 `__local__` 改写成真实 merchantCode（见 `AdoptionTx`），
 * 然后把这里切过去。收养必须由用户明确确认——它是不可逆的。
 */
object CurrentStore {

    private val _key = MutableStateFlow(LOCAL_STORE_KEY)

    /** 当前 storeKey 的响应式来源。Repository 用 `flatMapLatest` 观察它。 */
    val key: StateFlow<String> = _key

    /** 当前值（非响应式读取，例如写操作时取作用域）。 */
    val now: String get() = _key.value

    fun set(storeKey: String) {
        require(storeKey.isNotBlank()) { "storeKey 不能为空" }
        _key.value = storeKey
    }

    /** 登出/切换店铺时回到离线作用域。**不删数据**——原店铺的行还在，切回去就能看到。 */
    fun resetToLocal() {
        _key.value = LOCAL_STORE_KEY
    }
}
