package com.example.filterstudio.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class EditorHistory<T>(private val limit: Int = 30) {
    private val undo = ArrayDeque<T>()
    private val redo = ArrayDeque<T>()
    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()
    private val _canRedo = MutableStateFlow(false)
    val canRedo: StateFlow<Boolean> = _canRedo.asStateFlow()

    fun push(state: T) {
        undo.addLast(state)
        if (undo.size > limit) undo.removeFirst()
        redo.clear(); updateFlags()
    }
    fun undo(current: T): T? = undo.removeLastOrNull()?.also { redo.addLast(current); updateFlags() }
    fun redo(current: T): T? = redo.removeLastOrNull()?.also { undo.addLast(current); updateFlags() }
    fun clear() { undo.clear(); redo.clear(); updateFlags() }
    private fun updateFlags() { _canUndo.value = undo.isNotEmpty(); _canRedo.value = redo.isNotEmpty() }
}
