package com.example.myapplication.ui.ai

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication.data.ProductRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// 定义数据类来保存output项和选中状态
data class OutputItem(
    val text: String,
    val isSelected: Boolean = false
)

class AIViewModel(
    private val repository: ProductRepository
) : ViewModel() {

    // 预设输入列表
    private val _presetInputs = MutableStateFlow<List<String>>(emptyList())
    val presetInputs: StateFlow<List<String>> = _presetInputs.asStateFlow()

    // 修改为包含选中状态的OutputItem列表
    private val _output = MutableStateFlow<List<OutputItem>>(emptyList())
    val outputs: StateFlow<List<OutputItem>> = _output.asStateFlow()

    init {
        loadProductsWithoutWhole()
    }

    /**
     * 添加预设输入（自动分行处理）
     */
    fun addPresetInput(input: String) {
        val trimmedInput = input.trim()
        if (trimmedInput.isNotEmpty()) {
            val currentList = _presetInputs.value
            if (!currentList.contains(trimmedInput)) {
                _presetInputs.value = currentList + trimmedInput
            }
        }
    }

    fun addoutput(input: String) {
        if (input.isBlank()) return

        val lines = input.split("\n")
        val currentList = _output.value.toMutableList()
        var hasAddedNew = false

        lines.forEach { line ->
            val trimmedLine = line.trim()
            if (trimmedLine.isNotEmpty() && !currentList.any { it.text == trimmedLine }) {
                currentList.add(OutputItem(trimmedLine))
                hasAddedNew = true
            }
        }

        if (hasAddedNew) {
            _output.value = currentList
        }
    }

    /**
     * 移除指定索引的预设输入
     */
    fun removePresetInput(index: Int) {
        val currentList = _presetInputs.value.toMutableList()
        if (index in currentList.indices) {
            currentList.removeAt(index)
            _presetInputs.value = currentList
        }
    }

    fun removeoutput(index: Int) {
        val currentList = _output.value.toMutableList()
        if (index in currentList.indices) {
            currentList.removeAt(index)
            _output.value = currentList
        }
    }

    /**
     * 清空所有预设输入
     */
    fun clearPresetInputs() {
        _presetInputs.value = emptyList()
    }

    fun clearoutput() {
        _output.value = emptyList()

    }

    /**
     * 批量添加预设输入
     */
    fun addPresetInputs(inputs: List<String>) {
        val currentList = _presetInputs.value.toMutableList()
        inputs.forEach { input ->
            val trimmed = input.trim()
            if (trimmed.isNotEmpty() && !currentList.contains(trimmed)) {
                currentList.add(trimmed)
            }
        }
        _presetInputs.value = currentList
    }

    /**
     * 选择output项（标记为选中并移到最后）
     */
    fun selectoutput(index: Int): String? {
        val currentList = _output.value.toMutableList()
        return if (index in currentList.indices) {
            val selectedItem = currentList[index]
            // 1. 移除原位置的项
            currentList.removeAt(index)
            // 2. 创建标记为选中的新项
            val markedItem = selectedItem.copy(isSelected = true)
            // 3. 添加到列表末尾
            currentList.add(markedItem)
            // 4. 更新列表
            _output.value = currentList
            // 5. 返回选中的文本
            selectedItem.text
        } else {
            null
        }
    }

    fun selectPresetInput(index: Int): String? {
        val currentList = _presetInputs.value
        return if (index in currentList.indices) {
            val selected = currentList[index]
            removePresetInput(index)
            selected
        } else {
            null
        }
    }

    fun loadProductsWithoutWhole() {
        viewModelScope.launch {
            try {
                repository.getAllProducts().collect { products ->
                    val filteredNames = products
                        .filter { !it.name.contains("整件") }
                        .map { it.name }
                        .distinct()

                    _presetInputs.value = filteredNames
                }
            } catch (e: Exception) {
                _presetInputs.value = emptyList()
            }
        }
    }

    /**
     * 重置所有output项的选中状态
     */
    fun resetOutputSelection() {
        _output.update { list ->
            list.map { it.copy(isSelected = false) }
        }
    }
}