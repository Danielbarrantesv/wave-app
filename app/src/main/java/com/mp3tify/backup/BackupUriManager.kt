package com.mp3tify.backup

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences

/**
 * CRITICAL FIX (Problemas 1 y 3 — respaldo de URIs):
 * Persiste los tree URIs de las carpetas de música que el usuario seleccionó
 * con OpenDocumentTree. Estos URIs se guardan en SharedPreferences (que SÍ se
 * respalda con android:allowBackup a Google Drive) para que al reinstalar la
 * app podamos re-solicitar los persistable URI permissions y volver a leer
 * las carpetas de música.
 *
 * Sin esto, al reinstalar la app las URIs content:// del backup JSON caducan
 * (los persistable URI permissions se pierden al desinstalar) y las canciones
 * aparecen "sin ubicación" — no se pueden abrir ni reproducir.
 *
 * El flujo:
 *  1. Cuando el usuario selecciona una carpeta (OpenDocumentTree), llamamos a
 *     saveTreeUri(uri) que guarda el URI string en SharedPreferences.
 *  2. android:allowBackup respalda SharedPreferences a Google Drive.
 *  3. Al reinstalar, Android restaura SharedPreferences.
 *  4. Llamamos a restoreTreeUris() que re-solicita takePersistableUriPermission
 *     para cada URI guardado — así la app vuelve a tener acceso a las carpetas.
 */
object BackupUriManager {

    private const val PREFS_NAME = "wave_uri_backup"
    private const val KEY_TREE_URIS = "tree_uris"
    private const val KEY_LAST_BACKUP_TS = "last_backup_ts"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Guarda un tree URI (de OpenDocumentTree) en SharedPreferences. */
    fun saveTreeUri(context: Context, uri: android.net.Uri) {
        try {
            // Tomar el persistable permission AHORA para que la URI funcione.
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
        // Guardar el URI string en SharedPreferences.
        val current = prefs(context).getStringSet(KEY_TREE_URIS, emptySet()).orEmpty().toMutableSet()
        current.add(uri.toString())
        prefs(context).edit().putStringSet(KEY_TREE_URIS, current).apply()
    }

    /**
     * Re-solicita los persistable URI permissions para todos los tree URIs
     * guardados. Llamar al iniciar la app (MP3tifyApp.onCreate) para que las
     * carpetas de música sigan accesibles tras una reinstalación.
     * Retorna la lista de URIs que se pudieron restaurar (los que aún existen
     * y el sistema acepta el permission).
     */
    fun restoreTreeUris(context: Context): List<String> {
        val saved = prefs(context).getStringSet(KEY_TREE_URIS, emptySet()).orEmpty()
        if (saved.isEmpty()) return emptyList()
        val restored = mutableListOf<String>()
        // Intentar tomar el persistable permission para cada URI guardado.
        // Si el sistema ya tiene el permission concedido (no se perdió), esto es no-op.
        // Si se perdió (reinstalación), el takePersistableUriPermission puede fallar
        // — en ese caso el URI queda inservible y lo eliminamos de la lista.
        val stillValid = mutableListOf<String>()
        for (uriStr in saved) {
            try {
                val uri = android.net.Uri.parse(uriStr)
                // Verificar si el permission sigue activo consultando los persistent permissions.
                val perms = context.contentResolver.persistedUriPermissions
                val hasPerm = perms.any { it.uri == uri && it.isReadPermission }
                if (hasPerm) {
                    stillValid.add(uriStr)
                    restored.add(uriStr)
                } else {
                    // Intentar re-tomar el permission (puede fallar si la app fue reinstalada).
                    try {
                        context.contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        )
                        stillValid.add(uriStr)
                        restored.add(uriStr)
                    } catch (e: SecurityException) {
                        // El permission no se puede re-tomar — el URI caducó.
                        // Lo omitimos de stillValid (se elimina de la lista).
                        e.printStackTrace()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        // Actualizar la lista guardada con solo los que siguen válidos.
        if (stillValid.size != saved.size) {
            prefs(context).edit().putStringSet(KEY_TREE_URIS, stillValid.toSet()).apply()
        }
        return restored
    }

    /** Lista los tree URIs guardados (sin intentar re-solicitar permissions). */
    fun listTreeUris(context: Context): List<String> =
        prefs(context).getStringSet(KEY_TREE_URIS, emptySet()).orEmpty().toList()

    /** Marca el timestamp del último backup automático (para mostrar en Settings). */
    fun markBackupDone(context: Context) {
        prefs(context).edit().putLong(KEY_LAST_BACKUP_TS, System.currentTimeMillis()).apply()
    }

    /** Timestamp del último backup automático (0 si nunca). */
    fun lastBackupTs(context: Context): Long =
        prefs(context).getLong(KEY_LAST_BACKUP_TS, 0L)
}
