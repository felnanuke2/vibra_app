package br.com.felnanuke.mymusicapp.core.infrastructure.android.data_sources

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore.Audio
import android.util.Log
import androidx.annotation.RequiresApi
import br.com.felnanuke.mymusicapp.core.domain.entities.TrackEntity
import io.mockk.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AndroidTracksDataSourceTest {

    private lateinit var context: Context
    private lateinit var contentResolver: ContentResolver
    private lateinit var cursor: Cursor
    private lateinit var tracksDataSource: AndroidTracksDataSource

    private val testUri1 = mockk<Uri>()
    private val testUri2 = mockk<Uri>()
    
    // Additional test data constants
    private val testTrackId = 123L
    private val testTrackName = "Test Track"
    private val testArtistName = "Test Artist"
    private val testAlbumId = 456L
    private val testDuration = 180000L // 3 minutes
    private val testMimeType = "audio/mp3"

    @BeforeEach
    fun setUp() {
        // Mock Android framework classes first to prevent initialization issues
        mockkStatic(Log::class)
        every { Log.isLoggable(any(), any()) } returns false
        every { Log.d(any(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.v(any(), any()) } returns 0

        // Mock Bundle class
        mockkConstructor(Bundle::class)
        every { anyConstructed<Bundle>().clear() } just Runs
        every { anyConstructed<Bundle>().putInt(any(), any()) } just Runs
        every { anyConstructed<Bundle>().putString(any(), any()) } just Runs
        every { anyConstructed<Bundle>().putStringArray(any(), any()) } just Runs

        // Mock static methods
        mockkStatic(ContentUris::class)
        every { ContentUris.withAppendedId(any(), any()) } returns testUri1

        // Create mocks
        context = mockk(relaxed = true)
        contentResolver = mockk(relaxed = true)
        cursor = mockk(relaxed = true)

        every { context.contentResolver } returns contentResolver

        // Mock ContentResolver.refresh method
        every { contentResolver.refresh(any(), any(), any()) } returns true

        // Setup cursor to return test data by default
        every { cursor.getColumnIndexOrThrow(Audio.Media.DISPLAY_NAME) } returns 0
        every { cursor.getColumnIndexOrThrow(Audio.Media._ID) } returns 1
        every { cursor.getColumnIndexOrThrow(Audio.Media.ARTIST) } returns 2
        every { cursor.getColumnIndexOrThrow(Audio.Media.ALBUM_ID) } returns 3
        every { cursor.getColumnIndexOrThrow(Audio.Media.DURATION) } returns 4
        every { cursor.getColumnIndexOrThrow(Audio.Media.MIME_TYPE) } returns 5

        // Setup default cursor data
        every { cursor.moveToNext() } returnsMany listOf(true, false)
        every { cursor.getLong(1) } returns testTrackId
        every { cursor.getString(0) } returns testTrackName
        every { cursor.getString(2) } returns testArtistName
        every { cursor.getLong(3) } returns testAlbumId
        every { cursor.getLong(4) } returns testDuration
        every { cursor.getString(5) } returns testMimeType
        every { cursor.count } returns 1

        // Create the data source
        tracksDataSource = AndroidTracksDataSource(context)
    }

    @AfterEach
    fun tearDown() {
        clearAllMocks()
        unmockkStatic(Log::class)
        unmockkStatic(ContentUris::class)
        unmockkConstructor(Bundle::class)
    }

    @Test
    @Timeout(2)
    fun `getTracks should call onSuccess with tracks when query succeeds with bundle API`() {
        // Given
        val tracks = mutableListOf<TrackEntity>()
        var errorCalled = false

        // Mock successful bundle API query
        every { contentResolver.query(any<Uri>(), any(), any<Bundle>(), any()) } returns cursor

        // Mock cursor data for two tracks with valid durations (90+ seconds)
        every { cursor.moveToNext() } returnsMany listOf(true, true, false)
        every { cursor.getLong(1) } returnsMany listOf(1L, 2L)
        every { cursor.getString(0) } returnsMany listOf("Track 1", "Track 2")
        every { cursor.getString(2) } returnsMany listOf("Artist 1", "Artist 2")
        every { cursor.getLong(3) } returnsMany listOf(100L, 200L)
        every { cursor.getLong(4) } returnsMany listOf(120000L, 180000L) // Valid durations > 90s
        every { cursor.getString(5) } returnsMany listOf("audio/mp3", "audio/flac")

        // When
        tracksDataSource.getTracks(
            onSuccess = { tracks.addAll(it) },
            onError = { errorCalled = true }
        )

        // Then
        assertFalse(errorCalled)
        assertEquals(2, tracks.size)
        assertEquals("Track 1", tracks[0].name)
        assertEquals("Artist 1", tracks[0].artistName)
        assertEquals(1L, tracks[0].id)
        assertEquals(120000L, tracks[0].duration)
        assertEquals("Track 2", tracks[1].name)
        assertEquals("Artist 2", tracks[1].artistName)
        assertEquals(2L, tracks[1].id)
        assertEquals(180000L, tracks[1].duration)

        // Verify bundle API was attempted first
        verify { contentResolver.query(any<Uri>(), any(), any<Bundle>(), any()) }
    }

    @Test
    @Timeout(2)
    fun `getTracks should fallback to legacy API when bundle API fails`() {
        // Given
        val tracks = mutableListOf<TrackEntity>()
        var errorCalled = false

        // Mock bundle API failure, legacy API success
        every { contentResolver.query(any<Uri>(), any(), any<Bundle>(), any()) } returns null
        every { contentResolver.query(any<Uri>(), any(), any<String>(), any(), any<String>()) } returns cursor

        // Mock cursor behavior for one track with valid duration
        every { cursor.moveToNext() } returnsMany listOf(true, false)
        every { cursor.getLong(1) } returns 1L
        every { cursor.getString(0) } returns "Test Track"
        every { cursor.getString(2) } returns "Test Artist"
        every { cursor.getLong(3) } returns 100L
        every { cursor.getLong(4) } returns 150000L // Valid duration > 90s
        every { cursor.getString(5) } returns "audio/mp3"

        // When
        tracksDataSource.getTracks(
            onSuccess = { tracks.addAll(it) },
            onError = { errorCalled = true }
        )

        // Then
        assertFalse(errorCalled)
        assertEquals(1, tracks.size)
        assertEquals("Test Track", tracks[0].name)

        // Verify both APIs were attempted
        verify { contentResolver.query(any<Uri>(), any(), any<Bundle>(), any()) }
        verify { contentResolver.query(any<Uri>(), any(), any<String>(), any(), any<String>()) }
    }

    @Test
    @Timeout(2)
    fun `getTracks should fallback to no-filter query when both bundle and legacy APIs fail`() {
        // Given
        val tracks = mutableListOf<TrackEntity>()
        var errorCalled = false

        // Mock both APIs fail, no-filter succeeds
        every { contentResolver.query(any<Uri>(), any(), any<Bundle>(), any()) } returns null
        every { contentResolver.query(any<Uri>(), any(), any<String>(), any(), any<String>()) } returns null
        every { contentResolver.query(any<Uri>(), any(), null, null, any<String>()) } returns cursor

        // Mock cursor behavior with valid track
        every { cursor.moveToNext() } returnsMany listOf(true, false)
        every { cursor.getLong(1) } returns 1L
        every { cursor.getString(0) } returns "Test Track"
        every { cursor.getString(2) } returns "Test Artist"
        every { cursor.getLong(3) } returns 100L
        every { cursor.getLong(4) } returns 120000L // Valid duration > 90s
        every { cursor.getString(5) } returns "audio/wav"

        // When
        tracksDataSource.getTracks(
            onSuccess = { tracks.addAll(it) },
            onError = { errorCalled = true }
        )

        // Then
        assertFalse(errorCalled)
        assertEquals(1, tracks.size)

        // Verify all three query methods were attempted
        verify { contentResolver.query(any<Uri>(), any(), any<Bundle>(), any()) }
        verify { contentResolver.query(any<Uri>(), any(), any<String>(), any(), any<String>()) }
        verify { contentResolver.query(any<Uri>(), any(), null, null, any<String>()) }
    }

    @Test
    @Timeout(2)
    fun `getTracks should call onError when all query methods fail`() {
        // Given
        val tracks = mutableListOf<TrackEntity>()
        var errorCalled = false

        // Mock all queries fail
        every { contentResolver.query(any<Uri>(), any(), any<Bundle>(), any()) } returns null
        every { contentResolver.query(any<Uri>(), any(), any<String>(), any(), any<String>()) } returns null
        every { contentResolver.query(any<Uri>(), any(), null, null, any<String>()) } returns null

        // When
        tracksDataSource.getTracks(
            onSuccess = { tracks.addAll(it) },
            onError = { errorCalled = true }
        )

        // Then
        assertTrue(errorCalled)
        assertTrue(tracks.isEmpty())
    }

    @Test
    @Timeout(2)
    fun `getTracks should filter out tracks with short duration`() {
        // Given
        val tracks = mutableListOf<TrackEntity>()
        var errorCalled = false

        // Mock successful query
        every { contentResolver.query(any<Uri>(), any(), any<Bundle>(), any()) } returns cursor

        // One track with short duration (should be filtered), one with valid duration
        every { cursor.moveToNext() } returnsMany listOf(true, true, false)
        every { cursor.getLong(1) } returnsMany listOf(1L, 2L)
        every { cursor.getString(0) } returnsMany listOf("Short Track", "Long Track")
        every { cursor.getString(2) } returnsMany listOf("Artist 1", "Artist 2")
        every { cursor.getLong(3) } returnsMany listOf(100L, 200L)
        every { cursor.getLong(4) } returnsMany listOf(30000L, 120000L) // 30s vs 120s (90s minimum required)
        every { cursor.getString(5) } returnsMany listOf("audio/mp3", "audio/mp3")

        // When
        tracksDataSource.getTracks(
            onSuccess = { tracks.addAll(it) },
            onError = { errorCalled = true }
        )

        // Then
        assertFalse(errorCalled)
        assertEquals(1, tracks.size) // Only the long track should be included
        assertEquals("Long Track", tracks[0].name)
        assertEquals(120000L, tracks[0].duration)
    }

    @Test
    @Timeout(2)
    fun `getTracks should filter out non-music files`() {
        // Given
        val tracks = mutableListOf<TrackEntity>()
        var errorCalled = false

        // Mock successful query
        every { contentResolver.query(any<Uri>(), any(), any<Bundle>(), any()) } returns cursor

        // Mix of music and non-music files
        every { cursor.moveToNext() } returnsMany listOf(true, true, true, false)
        every { cursor.getLong(1) } returnsMany listOf(1L, 2L, 3L)
        every { cursor.getString(0) } returnsMany listOf("Music Track", "Ringtone", "Music Track 2")
        every { cursor.getString(2) } returnsMany listOf("Artist 1", "System", "Artist 2")
        every { cursor.getLong(3) } returnsMany listOf(100L, 200L, 300L)
        every { cursor.getLong(4) } returnsMany listOf(120000L, 120000L, 150000L) // All valid durations
        every { cursor.getString(5) } returnsMany listOf("audio/mp3", "audio/ringtone", "audio/flac")

        // When
        tracksDataSource.getTracks(
            onSuccess = { tracks.addAll(it) },
            onError = { errorCalled = true }
        )

        // Then
        assertFalse(errorCalled)
        assertEquals(2, tracks.size) // Only music tracks should be included
        assertEquals("Music Track", tracks[0].name)
        assertEquals("Music Track 2", tracks[1].name)
    }

    @Test
    @Timeout(2)
    fun `getTracks should call onError when exception occurs`() {
        // Given
        val tracks = mutableListOf<TrackEntity>()
        var errorCalled = false

        // Mock exception during query
        every { contentResolver.query(any<Uri>(), any(), any<Bundle>(), any()) } throws RuntimeException("Database error")

        // When
        tracksDataSource.getTracks(
            onSuccess = { tracks.addAll(it) },
            onError = { errorCalled = true }
        )

        // Then
        assertTrue(errorCalled)
        assertTrue(tracks.isEmpty())
    }

    @Test
    @Timeout(2)
    fun `getTrack should call onSuccess with track when found`() {
        // Given
        var resultTrack: TrackEntity? = null
        var errorException: Exception? = null
        val trackId = 123L

        // Mock successful query
        every { contentResolver.query(any<Uri>(), any(), any<String>(), any(), any()) } returns cursor

        // Mock cursor behavior - getTrack doesn't use use{}
        every { cursor.moveToNext() } returnsMany listOf(true, false)
        every { cursor.getString(0) } returns "Test Track"
        every { cursor.getString(2) } returns "Test Artist"
        every { cursor.getLong(3) } returns 100L
        every { cursor.getLong(4) } returns 180000L

        // When
        tracksDataSource.getTrack(
            id = trackId,
            onSuccess = { resultTrack = it },
            onError = { errorException = it }
        )

        // Then
        assertNull(errorException)
        assertNotNull(resultTrack)
        assertEquals(trackId, resultTrack!!.id)
        assertEquals("Test Track", resultTrack!!.name)
        assertEquals("Test Artist", resultTrack!!.artistName)
        assertEquals(180000L, resultTrack!!.duration)

        // Verify correct query was made
        verify { contentResolver.query(any<Uri>(), any(), "${Audio.Media._ID} = ?", arrayOf(trackId.toString()), null) }
    }

    @Test
    @Timeout(2)
    fun `getTrack should call onError when exception occurs`() {
        // Given
        var resultTrack: TrackEntity? = null
        var errorCalled = false
        val trackId = 123L

        // Mock exception during query
        every { contentResolver.query(any<Uri>(), any(), any<String>(), any(), any()) } throws RuntimeException("Database error")

        // When
        tracksDataSource.getTrack(
            id = trackId,
            onSuccess = { resultTrack = it },
            onError = { errorCalled = true }
        )

        // Then
        assertNull(resultTrack)
        assertTrue(errorCalled)
    }

    @Test
    @Timeout(2)
    fun `trackEntity should have functional getInputStream property`() {
        // Given
        val tracks = mutableListOf<TrackEntity>()
        var errorCalled = false

        // Mock InputStream
        val inputStream = mockk<InputStream>()
        every { contentResolver.openInputStream(any()) } returns inputStream

        // Mock successful query with one track
        every { contentResolver.query(any<Uri>(), any(), any<Bundle>(), any()) } returns cursor

        every { cursor.moveToNext() } returnsMany listOf(true, false)
        every { cursor.getLong(1) } returns 1L
        every { cursor.getString(0) } returns "Test Track"
        every { cursor.getString(2) } returns "Test Artist"
        every { cursor.getLong(3) } returns 100L
        every { cursor.getLong(4) } returns 120000L // Valid duration
        every { cursor.getString(5) } returns "audio/mp3"

        // When
        tracksDataSource.getTracks(
            onSuccess = { tracks.addAll(it) },
            onError = { errorCalled = true }
        )

        // Then
        assertFalse(errorCalled)
        assertEquals(1, tracks.size)
        
        val track = tracks[0]
        assertNotNull(track.getInputStream)

        // Test the getInputStream function
        val latch = CountDownLatch(1)
        var receivedInputStream: InputStream? = null
        track.getInputStream(track.audioUri) {
            receivedInputStream = it
            latch.countDown()
        }

        // Give the thread a moment to execute
        val completed = latch.await(1, TimeUnit.SECONDS)

        assertTrue(completed, "Callback was not invoked within the timeout period.")
        assertEquals(inputStream, receivedInputStream)
        verify { contentResolver.openInputStream(track.audioUri) }
    }

}

// Test-only Cursor extension functions for mocking
fun Cursor.getStringOrNull(columnIndex: Int): String? = getString(columnIndex)
fun Cursor.getLongOrNull(columnIndex: Int): Long? = getLong(columnIndex)