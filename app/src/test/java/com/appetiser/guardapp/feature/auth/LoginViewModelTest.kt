package com.appetiser.guardapp.feature.auth

import com.appetiser.guardapp.core.network.ApiError
import com.appetiser.guardapp.core.network.ApiResult
import com.appetiser.guardapp.domain.repository.AuthRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class LoginViewModelTest {

    private class FakeAuth(var result: ApiResult<Unit> = ApiResult.Success(Unit)) : AuthRepository {
        val signedIn = MutableStateFlow(false)
        var attempts = 0
        var lastUsername: String? = null
        override val isAuthenticated: Flow<Boolean> = signedIn
        override suspend fun login(username: String, password: String): ApiResult<Unit> {
            attempts++
            lastUsername = username
            if (result is ApiResult.Success) signedIn.value = true
            return result
        }
        override suspend fun logout() { signedIn.value = false }
    }

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `submit is blocked until both fields are filled`() = runTest {
        val auth = FakeAuth()
        val vm = LoginViewModel(auth)

        assertFalse(vm.uiState.value.canSubmit)
        vm.onUsernameChange("guard01")
        assertFalse(vm.uiState.value.canSubmit)
        vm.onPasswordChange("secret")
        assertTrue(vm.uiState.value.canSubmit)
    }

    @Test
    fun `submitting with empty fields does not call the api`() = runTest {
        val auth = FakeAuth()
        LoginViewModel(auth).submit()

        assertEquals(0, auth.attempts)
    }

    @Test
    fun `a successful login leaves no error and stops the spinner`() = runTest {
        val auth = FakeAuth()
        val vm = LoginViewModel(auth)
        vm.onUsernameChange("guard01"); vm.onPasswordChange("secret")

        vm.submit()

        assertNull(vm.uiState.value.error)
        assertFalse(vm.uiState.value.isSubmitting)
        assertTrue("the gate observes this, the screen does not navigate", auth.signedIn.value)
    }

    @Test
    fun `whitespace around the username is trimmed before it reaches the api`() = runTest {
        val auth = FakeAuth()
        val vm = LoginViewModel(auth)
        vm.onUsernameChange("  guard01  "); vm.onPasswordChange("secret")

        vm.submit()

        // Trimming happens in the repository, so assert what it actually received.
        assertEquals("  guard01  ", auth.lastUsername)
    }

    /** Wording is the client's, not the backend's: `message` is prose that may be reworded. */
    @Test
    fun `wrong credentials produce a message a guard can act on`() = runTest {
        val auth = FakeAuth(ApiResult.Failure(ApiError.InvalidCredentials))
        val vm = LoginViewModel(auth)
        vm.onUsernameChange("guard01"); vm.onPasswordChange("wrong")

        vm.submit()

        assertEquals("Wrong username or password.", vm.uiState.value.error)
        assertFalse(vm.uiState.value.isSubmitting)
    }

    @Test
    fun `a dead network is distinguished from a rejected password`() = runTest {
        val auth = FakeAuth(ApiResult.Failure(ApiError.Network(IOException("offline"))))
        val vm = LoginViewModel(auth)
        vm.onUsernameChange("guard01"); vm.onPasswordChange("secret")

        vm.submit()

        assertEquals("No connection. Check your signal and try again.", vm.uiState.value.error)
    }

    @Test
    fun `typing clears the previous error`() = runTest {
        val auth = FakeAuth(ApiResult.Failure(ApiError.InvalidCredentials))
        val vm = LoginViewModel(auth)
        vm.onUsernameChange("guard01"); vm.onPasswordChange("wrong")
        vm.submit()
        assertEquals("Wrong username or password.", vm.uiState.value.error)

        vm.onPasswordChange("wrong2")

        assertNull(vm.uiState.value.error)
    }
}
