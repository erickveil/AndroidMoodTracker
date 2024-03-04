package net.erickveil.moodtracker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import androidx.room.*
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.util.*

// Data entity representing a mood entry with a unique id, mood value, and timestamp
// This is part of the "Model"
@Entity
data class MoodEntry(

    // Primary key with auto-increment for unique entries
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    // Mood value (1-5) representing the user's mood
    val mood: Int,
    // Timestamp when the mood was recorded
    val timestamp: Long
)

// Data Access Object (DAO) for interacting with the MoodEntry table in the database
// This is where we would define all of our CRUD methods. We can also define common methods for:
// @Query for another specific READ
// @Update for modifying entries
// @Delete for removing entries
// This is basically our "Repository" class
@Dao
interface MoodEntryDao {

    // Insert a new mood entry into the database
    @Insert
    suspend fun insert(entry: MoodEntry)

    // Retrieve all mood entries, ordered by timestamp in descending order
    @Query("SELECT * FROM MoodEntry ORDER BY timestamp DESC")
    suspend fun getAll(): List<MoodEntry>
}

// Room database class for storing mood entries
// Entities are tables, which we easily define in a code-first way using the data class.
// This is basically our "Model"
@Database(entities = [MoodEntry::class], version = 1)
abstract class MoodDatabase : RoomDatabase() {

    // Provide access to the DAO for mood entries
    // Required for RoomDatabase classes
    abstract fun moodEntryDao(): MoodEntryDao
}

// Sealed class representing different user actions (intents) in the app
sealed class MoodTrackerIntent {

    // Intent to save a mood
    // Since this intent requires a value, we can't just use the `object` singleton here.
    data class SaveMood(val mood: Int) : MoodTrackerIntent()

    // Intent to load all mood entries
    object LoadEntries : MoodTrackerIntent()
}

// Data class representing the state of the mood tracker UI
// This is our immutable View State for MVI. We create a new one of these in the ViewModel when intents
// cause a state change. This allows Jetpack to dynamically update the View.
data class MoodTrackerState(

    // List of mood entries to display
    val moodEntries: List<MoodEntry> = emptyList(),

    // Flag indicating if data is currently loading
    val isLoading: Boolean = false
)

// ViewModel to manage the mood tracker state and handle user intents
// This is *mostly* a classic viewModel.
// We have the state manager so we can watch for state changes and update the UI accordingly.
// We have the Intent parser that responds to user activity.
//
// Normally, I would create a Repository class to act as a mediator between the ViewModel and the
// Model (the DAO, in this case is playing both roles). Then I could instantiate the Repository
// inside the ViewModel instead of having this injection complexity when I write a Unit Test.
class MoodTrackerViewModel(private val dao: MoodEntryDao) : ViewModel() {

    // LiveData holding the current state
    private val _state = MutableLiveData<MoodTrackerState>(MoodTrackerState(isLoading = true))

    // Public accessor for the state LiveData
    val state = _state

    init {
        // Load the initial set of mood entries when the ViewModel is created
        refreshEntries()
    }

    // Handle different intents (actions) from the UI using the DAO method we created above.
    fun handleIntent(intent: MoodTrackerIntent) {
        when (intent) {

            // This intent needs a little more to process its value, so we're going to give it a
            // whole code block. In more complex code, I'd offload this to its own method and just
            // call that.
            is MoodTrackerIntent.SaveMood -> {

                // Launch a coroutine to insert a new mood entry and refresh the data
                // Using a coroutine prevents us from blocking the UI.
                viewModelScope.launch {
                    dao.insert(MoodEntry(mood = intent.mood, timestamp = System.currentTimeMillis()))

                    // Refresh the list of mood entries after inserting a new one
                    refreshEntries()
                }
            }

            // Load all mood entries using the DAO method we created above.
            // Simple singleton and execution, so we don't need to dig into a is ... {} block to
            // call just one method.
            MoodTrackerIntent.LoadEntries -> refreshEntries()
        }
    }

    // Refresh the list of mood entries by fetching them from the database
    // All of our Intents wind up calling `refreshEntries` so that the UI shows new ones.
    private fun refreshEntries() {

        // More async behavior whenever we talk to the DB:
        viewModelScope.launch {

            // Get all mood entries from the database
            val entries = dao.getAll()

            // Update the state with the new list of entries
            // This in turn updates the View
            _state.postValue(MoodTrackerState(moodEntries = entries, isLoading = false))
        }
    }
}

// `AndroidEntryPoint` is Hilt annotation that tells us we're going to be using dependency injection.
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {

            // Build the Room database and get the DAO
            // Code-first construction using the classes we've created to define the DB structure.
            // Room will use an existing database if one exists. That way the data isn't overwritten.
            // BUT if the RoomDatabase class's `version` argument is changed, you need to provide
            // a migration strategy - it's assumed you've changed the database structure, and we
            // don't want the user to lose data (or crash).
            // We would define this in the `addMigrations()` method.
            val db = Room.databaseBuilder(applicationContext, MoodDatabase::class.java, "mood-db").build()
            val dao = db.moodEntryDao()

            // Create the ViewModel using the ViewModelProvider and the custom factory
            // We need to resort to the factory because we're injecting the dao into ViewModel.
            // Normally you can't just pass arguments to a class you've made from `ViewModel`
            val viewModel = ViewModelProvider(this, MoodTrackerViewModelFactory(dao)).get(MoodTrackerViewModel::class.java)

            // Observe the current state of the ViewModel
            // This gives the composable View the ability to change without explicitly telling it to.
            val state by viewModel.state.observeAsState(MoodTrackerState())

            // Display the UI with the current state and handle mood saving
            MoodTrackerScreen(state = state, onSaveMood = { mood ->
                viewModel.handleIntent(MoodTrackerIntent.SaveMood(mood))
            })
        }
    }
}

// Composable function to display the mood tracker screen
// This is the View! The usual Jetpack Compose stuff here.
@Composable
fun MoodTrackerScreen(state: MoodTrackerState, onSaveMood: (Int) -> Unit) {
    Scaffold { paddingValues ->

        // Column layout to center the content vertically and horizontally
        Column(
            modifier = Modifier.fillMaxSize().padding(paddingValues).padding(16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Select your mood:")

            // Row to display mood buttons (1-5)
            Row(modifier = Modifier.padding(top = 16.dp)) {
                for (mood in 1..5) {
                    Button(onClick = { onSaveMood(mood) }, modifier = Modifier.padding(4.dp)) {
                        // Button text showing the mood value
                        Text(mood.toString())
                    }
                }
            }
            Spacer(modifier = Modifier.height(32.dp))
            Text("Mood History:")

            // Display each mood entry with its mood value and timestamp
            // This is pretty basic. We probably add a VerticalScroll here.
            // TODO: Add a VerticalScroll here.
            for (entry in state.moodEntries) {
                Text(text = "Mood: ${entry.mood}, Time: ${Date(entry.timestamp)}")
            }
        }
    }
}

// Factory class to create the MoodTrackerViewModel with the DAO dependency
// Honestly, I just follow a template I have to define these.
class MoodTrackerViewModelFactory(private val dao: MoodEntryDao) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MoodTrackerViewModel::class.java)) {

            // Create the ViewModel if the class matches
            return MoodTrackerViewModel(dao) as T
        }
        // Throw an error if the class doesn't match
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
