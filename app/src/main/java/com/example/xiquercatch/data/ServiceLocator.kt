package com.example.xiquercatch.data

import android.content.Context
import com.example.xiquercatch.domain.ScheduleMergeService
import com.example.xiquercatch.export.CsvExporter

object ServiceLocator {
    @Volatile
    private var initialized = false

    lateinit var repository: ScheduleSnapshotRepository
        private set

    lateinit var mergeService: ScheduleMergeService
        private set

    lateinit var csvExporter: CsvExporter
        private set

    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val appContext = context.applicationContext
            repository = FileScheduleSnapshotRepository(appContext)
            mergeService = ScheduleMergeService()
            csvExporter = CsvExporter()
            initialized = true
        }
    }
}
