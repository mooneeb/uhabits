/*
 * Copyright (C) 2016-2025 Álinson Santos Xavier <git@axavier.org>
 *
 * This file is part of Loop Habit Tracker.
 *
 * Loop Habit Tracker is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 *
 * Loop Habit Tracker is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.isoron.uhabits.tasks

import android.util.Log
import org.isoron.platform.io.UserFile
import org.isoron.uhabits.core.models.ModelFactory
import org.isoron.uhabits.core.models.sqlite.SQLModelFactory
import org.isoron.uhabits.core.sync.TrackingImportReader
import org.isoron.uhabits.core.tasks.Task

class ImportDataTask(
    private val importer: TrackingImportReader,
    modelFactory: ModelFactory,
    private val file: UserFile,
    private val listener: Listener,
    private val context: android.content.Context
) : Task {
    private var result = 0
    private var failure: String? = null
    private val modelFactory: SQLModelFactory = modelFactory as SQLModelFactory
    override suspend fun doInBackground() {
        try {
            val backup = importer.read(file, java.util.UUID.randomUUID().toString())
            requireNotNull(modelFactory.changeStore) { "Synchronized import storage is unavailable." }.importBackup(backup)
            result = SUCCESS
        } catch (e: Exception) {
            result = FAILED
            Log.e("ImportDataTask", "Import failed", e)
            failure = e.message ?: "Import failed. Check the file format and update Loop if necessary."
        }
    }

    override fun onPostExecute() {
        (context.applicationContext as org.isoron.uhabits.HabitsApplication).driveSync?.refreshNativeModels()
        failure?.let {
            android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_LONG).show()
        }
        listener.onImportDataFinished(result)
    }

    fun interface Listener {
        fun onImportDataFinished(result: Int)
    }

    companion object {
        const val FAILED = 3
        const val NOT_RECOGNIZED = 2
        const val SUCCESS = 1
    }
}
