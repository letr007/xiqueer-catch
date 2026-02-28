package com.example.xiquercatch.data

import com.example.xiquercatch.data.model.ScheduleSnapshot
import com.example.xiquercatch.data.model.SemesterGroup
import kotlinx.coroutines.flow.StateFlow

interface ScheduleSnapshotRepository {
    val snapshots: StateFlow<List<ScheduleSnapshot>>

    fun saveSnapshot(snapshot: ScheduleSnapshot)

    fun getByIds(ids: Set<Long>): List<ScheduleSnapshot>

    fun deleteAll()

    fun getGroupedSnapshots(): List<SemesterGroup>
}
