package app.sentrek.record

import android.location.Location
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.sentrek.geo.LatLon
import app.sentrek.geo.place.Place
import app.sentrek.geo.place.PlaceKind
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.ExperimentalCoroutinesApi
import javax.inject.Inject

@HiltViewModel
class PlacesViewModel @Inject constructor(
    private val repository: PlaceRepository,
    private val location: LocationRepository,
) : ViewModel() {
    val places: StateFlow<List<Place>> = repository.places

    /** Bumped after a permission grant, to start the GPS. */
    private val permissionChecks = MutableStateFlow(0)

    /** GPS fixes while this screen is open: for distances and "Save my location". */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val fixes: StateFlow<Location?> = permissionChecks
        .flatMapLatest { if (location.hasPermission()) location.locations() else flowOf(null) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Where the user is, when known. */
    val here: StateFlow<LatLon?> = fixes
        .map { fix -> fix?.let { LatLon(it.latitude, it.longitude) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun hasLocationPermission() = location.hasPermission()

    fun onPermissionChanged() {
        permissionChecks.value++
    }

    /**
     * A new place at the user's position, for the edit dialog; waits up to a minute for a fix
     * of 50 m or better. Null when there's none.
     */
    fun draftHere(name: String, onReady: (Place?) -> Unit) {
        viewModelScope.launch {
            val fix = withTimeoutOrNull(FIX_TIMEOUT_MS) {
                fixes.first { it != null && it.accuracy <= MAX_ACCURACY_M }
            }
            onReady(fix?.let { draft(name, LatLon(it.latitude, it.longitude, LocationRepository.altitude(it))) })
        }
    }

    fun add(place: Place) {
        repository.add(place.name, place.kind, place.position, place.note, place.color)
    }

    fun update(place: Place) = repository.update(place)

    fun delete(place: Place) = repository.delete(place)

    fun export(target: Uri, onDone: (Boolean) -> Unit) {
        viewModelScope.launch { onDone(repository.export(target)) }
    }

    companion object {
        private const val FIX_TIMEOUT_MS = 60_000L
        private const val MAX_ACCURACY_M = 50f

        /** A place not saved yet, for [PlaceDialog]. */
        fun draft(name: String, position: LatLon, kind: PlaceKind = PlaceKind.Star) = Place("", name, kind, position)
    }
}
