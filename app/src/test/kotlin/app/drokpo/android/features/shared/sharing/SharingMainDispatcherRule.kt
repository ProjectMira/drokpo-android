package app.drokpo.android.features.shared.sharing

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * Routes `viewModelScope` (Dispatchers.Main.immediate) onto a [StandardTestDispatcher] so launched
 * work waits until the test advances the scheduler — in-flight states ("sending", "liking") stay
 * observable. Pass [dispatcher] to `runTest` to share the virtual clock. (Group-private copy, so
 * these tests don't depend on another group's test helpers.)
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SharingMainDispatcherRule(val dispatcher: TestDispatcher = StandardTestDispatcher()) : TestWatcher() {
    override fun starting(description: Description) = Dispatchers.setMain(dispatcher)

    override fun finished(description: Description) = Dispatchers.resetMain()
}
