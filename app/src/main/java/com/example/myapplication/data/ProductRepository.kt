package com.example.myapplication.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

class ProductRepository(private val productDao: ProductDao) {
    fun getAllProducts(): Flow<List<Product>> = productDao.getAllProducts()
    
    suspend fun getProductByBarcode(barcode: String): Product? = productDao.getProductByBarcode(barcode)
    
    suspend fun insertProduct(product: Product) { productDao.insertProduct(product)
        cachedProducts=getAllProducts().first()
    }
    
    suspend fun updateProduct(product: Product) {
        productDao.updateProduct(product)
        cachedProducts=getAllProducts().first()
    }
    
    suspend fun deleteProduct(barcode: String) {
        productDao.deleteProduct(barcode)
        cachedProducts=getAllProducts().first()
    }


    suspend fun searchProducts(query: String) = productDao.searchProductsByName(query)


    private var cachedProducts: List<Product>? = null
    suspend fun searchProductsByCharacters(query: String): List<Product> {
        // 1. 检查查询是否有效
        if (query.isBlank()) {
            return emptyList()
        }

        // 3. 分割查询为 tokens（字符）
        val tokens = splitQueryIntoTokens(query)
        if (tokens.isEmpty()) {
            return emptyList()
        }

        if(cachedProducts==null){
            cachedProducts=getAllProducts().first()
        }

        val allProducts = cachedProducts


        val results = allProducts?.filter { product ->
            val productName = product.name
            val barcode=product.barcode
            tokens.any { token ->
                matchesNum(productName, token,barcode)
            }
        }
       return results
           ?.map { product ->
               // 预先计算一次，将结果缓存起来
               product to calculateMatchScore(product, tokens)
           }
           ?.sortedWith(
               compareByDescending<Pair<Product, Pair<Int, Int>>> { it.second.first }
                   .thenByDescending { it.second.second }
                   .thenBy { it.first.name.length }
           )
           ?.map { it.first } ?: emptyList()

    }

    /**
     * 获取所有商品列表（非 Flow）
     */
    private suspend fun getAllProductsDirect(str:String): List<Product> {
        // 通过 Flow 获取数据并转换为 List
        return try {
            productDao.searchProductsByName(str)
        } catch (e: Exception) {
            emptyList()
        }
    }
    private fun matchesToken(productName: String, token: String,barcode: String): Boolean {
        if(barcode[0]=='0'){
            return false
        }
        // 如果 token 是纯数字，进行精确匹配
        if (token.matches(Regex("\\d+"))) {
            // 使用正则表达式确保数字精确匹配
            // \\D 表示非数字，? 表示 0 或 1 个
            // 匹配模式：前面是非数字或开头，后面是非数字或结尾
            val pattern = "(?<!\\d)$token(?!\\d)"
            return Regex(pattern).containsMatchIn(productName)
        } else {
            // 非数字 token，直接包含匹配
            return productName.contains(token)
        }
    }
    private fun matchesNum(productName: String, token: String,barcode: String): Boolean {
        if(barcode[0]=='0'){
            return false
        }
        // 如果 token 是纯数字，进行精确匹配
        if (token.matches(Regex("\\d+"))) {
            // 使用正则表达式确保数字精确匹配
            // \\D 表示非数字，? 表示 0 或 1 个
            // 匹配模式：前面是非数字或开头，后面是非数字或结尾
            val pattern = "(?<!\\d)$token(?!\\d)"
            return Regex(pattern).containsMatchIn(productName)
        } else {
            // 非数字 token，直接包含匹配
            return productName.contains(token)
        }
    }


    /**
     * 分割查询字符串为 tokens
     * 规则：
     * 1. 连续数字作为一个整体
     * 2. 其他字符每个单独分割
     * 示例："苹果13手机" -> ["苹", "果", "13", "手", "机"]
     */
    private fun splitQueryIntoTokens(query: String): List<String> {
        val tokens = mutableListOf<String>()
        var i = 0
        val length = query.length

        while (i < length) {
            val currentChar = query[i]

            // 处理连续数字
            if (currentChar.isDigit()) {
                val start = i
                while (i < length && query[i].isDigit()) {
                    i++
                }
                tokens.add(query.substring(start, i))
            }
            // 处理其他字符（每个单独）
            else {
                tokens.add(currentChar.toString())
                i++
            }
        }

        return tokens.filter { it.isNotBlank() }
    }

    /**
     * 计算匹配分数
     */
    private fun calculateMatchScore(product: Product, tokens: List<String>): Pair<Int, Int> {
        val productName = product.name
        var score = 0

        tokens.forEach { token ->
            if (productName.contains(token)) {
                score += 1000

            }
        }

        return Pair(score, score / productName.length)
    }
}


