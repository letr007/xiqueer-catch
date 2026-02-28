package com.example.xiquercatch.data

import android.content.Context
import com.example.xiquercatch.data.model.ScheduleSnapshot
import com.example.xiquercatch.data.model.SemesterGroup
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class FileScheduleSnapshotRepository(
    context: Context
) : ScheduleSnapshotRepository {

    private val file = File(context.filesDir, FILE_NAME)
    private val gson = Gson()
    private val lock = ReentrantLock()
    private val listType = object : TypeToken<MutableList<ScheduleSnapshot>>() {}.type

    private val mutableSnapshots = MutableStateFlow(loadSnapshots())

    override val snapshots: StateFlow<List<ScheduleSnapshot>> = mutableSnapshots

    override fun saveSnapshot(snapshot: ScheduleSnapshot) {
        lock.withLock {
            val current = mutableSnapshots.value.toMutableList()
            val index = current.indexOfFirst {
                it.xn == snapshot.xn &&
                    it.xq == snapshot.xq &&
                    it.zc == snapshot.zc
            }
            if (index >= 0) {
                current[index] = snapshot.copy(id = current[index].id)
            } else {
                current.add(snapshot)
            }
            current.sortWith(
                compareBy<ScheduleSnapshot> { it.xn }
                    .thenBy { it.xq }
                    .thenBy { it.zc }
            )
            persist(current)
            mutableSnapshots.value = current
        }
    }

    override fun getByIds(ids: Set<Long>): List<ScheduleSnapshot> {
        if (ids.isEmpty()) return emptyList()
        return mutableSnapshots.value.filter { it.id in ids }
    }

    override fun deleteAll() {
        lock.withLock {
            mutableSnapshots.value = emptyList()
            persist(emptyList())
        }
    }

    override fun getGroupedSnapshots(): List<SemesterGroup> {
        return mutableSnapshots.value
            .groupBy { "${it.xn}-${it.xq}" }
            .toSortedMap()
            .map { (key, items) ->
                SemesterGroup(
                    key = key,
                    title = "${items.first().xn} 学年 第${displaySemester(items.first().xq)}学期",
                    snapshots = items.sortedBy { it.zc }
                )
            }
    }

    private fun displaySemester(rawXq: String): String {
        val value = rawXq.toIntOrNull() ?: return rawXq
        return (value + 1).toString()
    }

    private fun loadSnapshots(): List<ScheduleSnapshot> {
        if (!file.exists()) {
            return emptyList()
        }
        return runCatching {
            file.readText(Charsets.UTF_8)
                .takeIf { it.isNotBlank() }
                ?.let { gson.fromJson<MutableList<ScheduleSnapshot>>(it, listType) }
                ?.toList()
                .orEmpty()
        }.getOrDefault(emptyList())
    }

    private fun persist(snapshots: List<ScheduleSnapshot>) {
        file.writeText(gson.toJson(snapshots), Charsets.UTF_8)
    }

    companion object {
        private const val FILE_NAME = "schedule_snapshots.json"
    }
}
