package dev.kitlangton.scrawl.autoinstall

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import dev.kitlangton.scrawl.Scrawl

/**
 * Adding `debugImplementation("dev.kitlangton.scrawl:scrawlkit-autoinstall")` is the whole
 * integration: this provider calls [Scrawl.install] as the app process starts (three-finger long
 * press, enabled), so the app needs no code. It stays inert until the device is paired. Apps that
 * want a different trigger, or `Scrawl.event`/`context`, depend on `scrawlkit` and call
 * `Scrawl.install` themselves (calling it again just updates the trigger).
 */
class ScrawlAutoInstall : ContentProvider() {
    override fun onCreate(): Boolean {
        (context?.applicationContext as? Application)?.let { Scrawl.install(it) }
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
