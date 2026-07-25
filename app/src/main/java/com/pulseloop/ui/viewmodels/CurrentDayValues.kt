package com.pulseloop.ui.viewmodels

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal fun <K, T> currentDayValues(
    dayKeys: StateFlow<K>,
    valuesForDay: (K) -> Flow<T>,
): Flow<T> = dayKeys.flatMapLatest(valuesForDay)
