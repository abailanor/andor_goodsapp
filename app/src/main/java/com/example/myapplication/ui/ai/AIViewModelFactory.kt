package com.example.myapplication.ui.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.myapplication.data.ProductRepository

/**
 * AIViewModel 工厂类
 */
class AIViewModelFactory(
    private val repository: ProductRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(AIViewModel::class.java)) {
            return AIViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

