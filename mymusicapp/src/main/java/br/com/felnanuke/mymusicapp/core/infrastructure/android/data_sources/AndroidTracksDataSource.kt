package br.com.felnanuke.mymusicapp.core.infrastructure.android.data_sources

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore.Audio
import android.util.Size
import androidx.annotation.RequiresApi
import androidx.core.database.getLongOrNull
import androidx.core.database.getStringOrNull
import androidx.documentfile.provider.DocumentFile
import br.com.felnanuke.mymusicapp.core.domain.data_sources.ITracksDataSource
import br.com.felnanuke.mymusicapp.core.domain.entities.TrackEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.InputStream
import java.util.concurrent.CountedCompleter
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class AndroidTracksDataSource(private val context: Context) : ITracksDataSource {


    private val collection = Audio.Media.EXTERNAL_CONTENT_URI
    private val contentResolver = context.contentResolver
    private val projection = arrayOf(
        Audio.Media._ID,
        Audio.Media.DISPLAY_NAME,
        Audio.Media.ALBUM_ID,
        Audio.Media.ARTIST,
        Audio.Media.DURATION,
        Audio.Media.MIME_TYPE
    )

    private val selection = "${Audio.Media.IS_MUSIC} = 1 AND ${Audio.Media.DURATION} > ? AND ${Audio.Media.MIME_TYPE} LIKE 'audio/%' AND ${Audio.Media.MIME_TYPE} NOT LIKE '%ringtone%' AND ${Audio.Media.MIME_TYPE} NOT LIKE '%notification%' AND ${Audio.Media.MIME_TYPE} NOT LIKE '%alarm%'"


    private var bundle = Bundle()
    private val selectionArgs = arrayOf(
        TimeUnit.MILLISECONDS.convert(30, TimeUnit.SECONDS).toString() // Increased to 30 seconds minimum
    )

    private val sortOrder = "${Audio.Media.DATE_MODIFIED} DESC"


    @RequiresApi(Build.VERSION_CODES.O)
    override fun getTracks(onSuccess: (Array<TrackEntity>) -> Unit, onError: () -> Unit) {
        try {
            contentResolver.refresh(Audio.Media.EXTERNAL_CONTENT_URI, null, null)
            bundle.clear()
            var tracks = arrayOf<TrackEntity>()
            bundle.putInt(ContentResolver.QUERY_ARG_LIMIT, 999)
            bundle.putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
            bundle.putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, selectionArgs)
            bundle.putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, sortOrder)

            println("AndroidTracksDataSource: Attempting to query with bundle API...")
            var query = context.contentResolver.query(collection, projection, bundle, null)
            
            // Fallback to old API if bundle API fails
            if (query == null) {
                println("AndroidTracksDataSource: Bundle API failed, trying legacy API...")
                query = context.contentResolver.query(
                    collection, 
                    projection, 
                    selection, 
                    selectionArgs, 
                    sortOrder
                )
            }
            
            // Last resort - try without any filters
            if (query == null) {
                println("AndroidTracksDataSource: Legacy API failed, trying without filters...")
                query = context.contentResolver.query(
                    collection, 
                    projection, 
                    null, 
                    null, 
                    sortOrder
                )
            }
            
            if (query == null) {
                println("AndroidTracksDataSource: All query methods failed")
                onError()
                return
            }

            query.use { cursor ->
                val nameColumnIndex = cursor.getColumnIndexOrThrow(Audio.Media.DISPLAY_NAME)
                val idColumnIndex = cursor.getColumnIndexOrThrow(Audio.Media._ID)
                val artistColumIndex = cursor.getColumnIndexOrThrow(Audio.Media.ARTIST)
                val albumIdColumnIndex = cursor.getColumnIndexOrThrow(Audio.Media.ALBUM_ID)
                val trackDurationColumnIndex = cursor.getColumnIndexOrThrow(Audio.Media.DURATION)
                val mimeTypeColumnIndex = cursor.getColumnIndexOrThrow(Audio.Media.MIME_TYPE)

                println("AndroidTracksDataSource: Found ${cursor.count} media files")
                
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumnIndex)
                    val name = cursor.getStringOrNull(nameColumnIndex) ?: "undefined"
                    val artist = cursor.getStringOrNull(artistColumIndex) ?: "undefined"
                    val albumId = cursor.getLongOrNull(albumIdColumnIndex)
                    val audioUri = ContentUris.withAppendedId(Audio.Media.EXTERNAL_CONTENT_URI, id)
                    val duration = cursor.getLongOrNull(trackDurationColumnIndex)
                    val mimeType = cursor.getStringOrNull(mimeTypeColumnIndex)
                    
                    // Additional filtering for music files only
                    val isMusicFile = mimeType?.let { mime ->
                        mime.startsWith("audio/") && 
                        !mime.contains("ringtone", ignoreCase = true) &&
                        !mime.contains("notification", ignoreCase = true) &&
                        !mime.contains("alarm", ignoreCase = true) &&
                        !name.contains("ringtone", ignoreCase = true) &&
                        !name.contains("notification", ignoreCase = true) &&
                        !name.contains("alarm", ignoreCase = true) &&
                        !name.startsWith("AUD-", ignoreCase = true) &&
                        duration != null && duration >= 90000 // 90 seconds minimum duration
                    } ?: false
                    
                    // Only add tracks that are valid music files
                    if (isMusicFile && duration != null && duration > 0) {
                        val albumImage = if (albumId != null) ContentUris.withAppendedId(
                            Audio.Albums.EXTERNAL_CONTENT_URI, albumId
                        ) else null
                        val track = TrackEntity(
                            id,
                            name,
                            artist,
                            audioUri,
                            albumImage,
                            duration,
                            getAudioByteStream = { uri, completer ->
                                this.getAudioBytes(
                                    uri, completer
                                )
                            },
                        )
                        tracks += track
                        println("AndroidTracksDataSource: Added track: $name by $artist (${duration}ms) - $mimeType")
                    } else {
                        println("AndroidTracksDataSource: Skipped file $name - not a music file or invalid duration: $duration, mimeType: $mimeType")
                    }
                }

                println("AndroidTracksDataSource: Total valid tracks found: ${tracks.size}")
                onSuccess(tracks)
            }
        } catch (e: Exception) {
            println("AndroidTracksDataSource: Error loading tracks: ${e.message}")
            e.printStackTrace()
            onError()
        }
    }

    override fun getTrack(
        id: Long, onSuccess: (TrackEntity) -> Unit, onError: (Exception) -> Unit
    ) {
        val selection = "${Audio.Media._ID} = ?"
        val selectionArgs = arrayOf(id.toString())
        try {
            val query =
                context.contentResolver.query(collection, projection, selection, selectionArgs, null)

            if (query == null) {
                onError(Exception("Query failed, returned null cursor."))
                return
            }

            query.use { cursor ->
                val nameColumnIndex = cursor.getColumnIndexOrThrow(Audio.Media.DISPLAY_NAME)
                val artistColumIndex = cursor.getColumnIndexOrThrow(Audio.Media.ARTIST)
                val albumIdColumnIndex = cursor.getColumnIndexOrThrow(Audio.Media.ALBUM_ID)
                val trackDurationColumnIndex = cursor.getColumnIndexOrThrow(Audio.Media.DURATION)

                if (cursor.moveToNext()) {
                    val name = cursor.getStringOrNull(nameColumnIndex) ?: "undefined"
                    val artist = cursor.getStringOrNull(artistColumIndex) ?: "undefined"
                    val albumId = cursor.getLongOrNull(albumIdColumnIndex)
                    val audioUri = ContentUris.withAppendedId(Audio.Media.EXTERNAL_CONTENT_URI, id)
                    val duration = cursor.getLongOrNull(trackDurationColumnIndex)
                    val albumImage = if (albumId != null) ContentUris.withAppendedId(
                        Audio.Albums.EXTERNAL_CONTENT_URI, albumId
                    ) else null
                    val track = TrackEntity(
                        id,
                        name,
                        artist,
                        audioUri,
                        albumImage,
                        duration ?: 0,
                        getAudioByteStream = { uri, completer ->
                            this.getAudioBytes(
                                uri, completer
                            )
                        },
                    )
                    onSuccess(track)
                } else {
                    onError(Exception("Track with id $id not found."))
                }
            }
        } catch (e: Exception) {
            onError(e)
        }
    }

    private fun getAudioBytes(
        audioUri: Uri, completer: ((InputStream?) -> Unit)? = null
    ) {
        thread {
            completer?.invoke(context.contentResolver.openInputStream(audioUri))
        }
    }


}