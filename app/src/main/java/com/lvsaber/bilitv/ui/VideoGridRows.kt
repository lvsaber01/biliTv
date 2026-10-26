package com.lvsaber.bilitv.ui

/** Headers consume a full span but never become a D-pad destination. */
internal object VideoGridRows {
    fun rows(headers: List<Boolean>, columns: Int): List<List<Int>> {
        require(columns > 0)
        val result = mutableListOf<List<Int>>()
        var row = mutableListOf<Int>()
        headers.forEachIndexed { index, header ->
            if (header) {
                if (row.isNotEmpty()) result += row.toList()
                row = mutableListOf()
            } else {
                row += index
                if (row.size == columns) { result += row.toList(); row = mutableListOf() }
            }
        }
        if (row.isNotEmpty()) result += row.toList()
        return result
    }
}
