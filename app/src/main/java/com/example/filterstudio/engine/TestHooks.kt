package com.example.filterstudio.engine

import com.example.filterstudio.model.FilterType

/** Test-only accessor — the app module never calls this. */
fun FilterEngine.filterMatrixInternalForTest(type: FilterType): FloatArray =
    FilterEngine.javaClass.getDeclaredMethod("filterMatrix", FilterType::class.java).let {
        it.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        it.invoke(this, type) as FloatArray
    }
