package com.minsu.guardapp.core.security

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Runs on device because AndroidKeyStore has no JVM implementation — the encryption this
 * class exists to provide cannot be tested on the host.
 */
@RunWith(AndroidJUnit4::class)
class KeystoreTokenStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val storeName = "test_session_${System.nanoTime()}"

    private lateinit var file: File
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var store: TokenStore

    private val token = "1|aBcDeF0123456789SanctumPlainTextToken"

    @Before
    fun setUp() {
        file = context.preferencesDataStoreFile(storeName)
        dataStore = PreferenceDataStoreFactory.create { file }
        store = KeystoreTokenStore(dataStore)
    }

    @After
    fun tearDown() {
        file.delete()
    }

    @Test
    fun round_trips_a_token() = runTest {
        assertNull("nothing stored yet", store.token())

        store.save(token)

        assertEquals(token, store.token())
    }

    /** The point of the whole class: the token must not be readable from the file. */
    @Test
    fun the_token_is_not_recoverable_from_the_file_on_disk() = runTest {
        store.save(token)

        val bytes = file.readBytes()
        val asText = String(bytes, Charsets.ISO_8859_1)

        assertFalse("plaintext token found on disk", asText.contains(token))
        assertFalse("token fragment found on disk", asText.contains("SanctumPlainTextToken"))
    }

    @Test
    fun clear_removes_the_token() = runTest {
        store.save(token)
        store.clear()

        assertNull(store.token())
    }

    @Test
    fun clear_is_idempotent_when_already_logged_out() = runTest {
        store.clear()
        store.clear()

        assertNull(store.token())
    }

    @Test
    fun overwriting_a_token_yields_the_newer_one() = runTest {
        store.save(token)
        store.save("2|replacementToken")

        assertEquals("2|replacementToken", store.token())
    }

    /**
     * GCM requires a unique IV per encryption. Encrypting the same token twice must not
     * produce the same ciphertext; if it did, the IV would be reused and the mode broken.
     */
    @Test
    fun encrypting_the_same_token_twice_produces_different_ciphertext() = runTest {
        store.save(token)
        val first = file.readBytes().copyOf()

        store.save(token)
        val second = file.readBytes()

        assertFalse("IV appears to be reused", first.contentEquals(second))
        assertEquals(token, store.token())
    }

    /**
     * A restored backup or a wiped keystore leaves ciphertext whose key is gone. Reading it
     * must degrade to logged-out, not throw on every launch forever.
     *
     * The ciphertext value is corrupted directly rather than the file's bytes: mangling the
     * protobuf would trip DataStore's own parser instead of the decrypt path under test.
     */
    @Test
    fun undecryptable_ciphertext_forces_a_fresh_login_rather_than_failing_forever() = runTest {
        store.save(token)
        dataStore.edit { it[CIPHERTEXT] = "dGhpcyBpcyBub3QgdmFsaWQgY2lwaGVydGV4dA==" }

        assertNull("must degrade to logged-out, not throw", store.token())
        // ...and the unusable value is dropped, so it cannot fail again on the next launch.
        assertNull(dataStore.data.first()[CIPHERTEXT])
    }

    private companion object {
        val CIPHERTEXT = stringPreferencesKey("session_token_ciphertext")
    }
}
