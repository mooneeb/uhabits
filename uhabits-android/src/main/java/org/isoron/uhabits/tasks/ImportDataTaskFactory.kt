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

import android.content.Context
import me.tatarka.inject.annotations.Inject
import org.isoron.platform.io.DatabaseOpener
import org.isoron.platform.io.FileOpener
import org.isoron.platform.io.UserFile
import org.isoron.uhabits.core.commands.CommandRunner
import org.isoron.uhabits.core.models.ModelFactory
import org.isoron.uhabits.core.sync.TrackingImportReader
import org.isoron.uhabits.inject.AppContext

@Inject
class ImportDataTaskFactory(
    private val opener: DatabaseOpener,
    private val files: FileOpener,
    private val runner: CommandRunner,
    private val modelFactory: ModelFactory,
    @param:AppContext private val context: Context
) {
    fun create(file: UserFile, listener: ImportDataTask.Listener) =
        ImportDataTask(TrackingImportReader(opener, files, runner), modelFactory, file, listener, context)
}
