/*
 *    Transportr
 *
 *    Copyright (c) 2013 - 2021 Torsten Grote
 *
 *    This program is Free Software: you can redistribute it and/or modify
 *    it under the terms of the GNU General Public License as
 *    published by the Free Software Foundation, either version 3 of the
 *    License, or (at your option) any later version.
 *
 *    This program is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU General Public License for more details.
 *
 *    You should have received a copy of the GNU General Public License
 *    along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package de.grobox.transportr.trips.search

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.util.Pair
import android.view.LayoutInflater
import android.view.View
import android.view.View.GONE
import android.view.View.VISIBLE
import android.view.ViewGroup
import android.widget.Toast
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.omadahealth.github.swipyrefreshlayout.library.SwipyRefreshLayout.OnRefreshListener
import com.omadahealth.github.swipyrefreshlayout.library.SwipyRefreshLayoutDirection
import de.grobox.transportr.R
import de.grobox.transportr.TransportrFragment
import de.grobox.transportr.trips.detail.TripDetailActivity
import de.grobox.transportr.trips.search.TripAdapter.OnTripClickListener
import de.grobox.transportr.trips.search.TripsRepository.QueryMoreState
import de.grobox.transportr.ui.LceAnimator
import de.grobox.transportr.utils.Linkify
import de.schildbach.pte.dto.Trip
import kotlinx.android.synthetic.main.fragment_trips.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Timer
import java.util.TimerTask
import java.util.regex.Pattern
import javax.inject.Inject

class TripsFragment : TransportrFragment(), OnRefreshListener, OnTripClickListener {
    
    @Inject
    internal lateinit var viewModelFactory: ViewModelProvider.Factory
    
    private lateinit var viewModel: DirectionsViewModel
    
    private val adapter = TripAdapter(this)
    private var queryMoreDirection = SwipyRefreshLayoutDirection.BOTH
    private var secondsSinceUpdate: Int = 0

    private var timer = Timer()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val v = inflater.inflate(R.layout.fragment_trips, container, false)
        component.inject(this)

        viewModel = ViewModelProvider(activity!!, viewModelFactory).get(DirectionsViewModel::class.java)

        return v
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Progress Bar and Error View
        errorButton.setOnClickListener { viewModel.search() }

        swipe.isEnabled = false // disable swipe, replaced with buttons

        val layoutManager = LinearLayoutManager(context)
        list.layoutManager = layoutManager
        list.setHasFixedSize(false)
        viewModel.queryMoreState.observe(viewLifecycleOwner, { state -> updateSearchMoreState(state) })
        viewModel.trips.observe(viewLifecycleOwner, { trips -> onTripsLoaded(trips) })
        viewModel.queryError.observe(viewLifecycleOwner, { error -> onError(error) })
        viewModel.queryPTEError.observe(viewLifecycleOwner, { error -> onPTEError(error) })
        viewModel.queryMoreError.observe(viewLifecycleOwner, { error -> onMoreError(error) })
        viewModel.timeUpdate.observe(viewLifecycleOwner, { onTimeUpdate() })
        adapter.setHasStableIds(false)
        list.adapter = adapter
        LceAnimator.showLoading(progressBar, list, errorLayout)

        search_more_earlier.setOnClickListener {
            updateSearchMoreState(QueryMoreState.NONE)
            searchMoreEarlierProgressBar.visibility = VISIBLE
            viewModel.searchMore(false)
        }
        search_more_later.setOnClickListener {
            updateSearchMoreState(QueryMoreState.NONE)
            viewModel.searchMore(true)
            searchMoreLaterProgressBar.visibility = VISIBLE
        }
    }

    private fun onTimeUpdate() {
        adapter.notifyDataSetChanged()
        secondsSinceUpdate = 0
    }

    override fun onRefresh(direction: SwipyRefreshLayoutDirection) {
        // removed, because i don't like it
        // replaced with buttons
    }

    private fun updateSearchMoreState(state: QueryMoreState?) {
        /*val topEnabled = viewModel.topSwipeEnabled.value
        if (topEnabled == null || state == null) return*/
        if (state === QueryMoreState.NONE) {
            search_more_earlier.isEnabled = false
            search_more_later.isEnabled = false
        } else {
            search_more_earlier.isEnabled = true
            search_more_later.isEnabled = true
            searchMoreEarlierProgressBar.visibility = GONE
            searchMoreLaterProgressBar.visibility = GONE
        }
    }

    private fun onTripsLoaded(trips: Set<Trip>?) {
        if (trips == null) return
        val oldCount = adapter.itemCount
        adapter.addAll(trips)
        if (oldCount > 0) {
            // swipe.isRefreshing = false
            list.smoothScrollBy(0, if (queryMoreDirection == SwipyRefreshLayoutDirection.BOTTOM) 200 else -200)
        } else {
            LceAnimator.showContent(progressBar, list, errorLayout)
        }
    }

    private fun onError(error: String?) {
        if (error == null) return
        errorText.text = error
        LceAnimator.showErrorView(progressBar, list, errorLayout)
    }

    @SuppressLint("SetTextI18n")
    private fun onPTEError(error: Pair<String, String>?) {
        if (error == null) return
        errorText.text = "${error.first}\n\n${getString(R.string.trip_error_pte, "public-transport-enabler")}"
        val pteMatcher = Pattern.compile("public-transport-enabler")
        Linkify.addLinks(errorText, pteMatcher, error.second)
        LceAnimator.showErrorView(progressBar, list, errorLayout)
    }

    private fun onMoreError(error: String?) {
        if (error == null) return
        // swipe.isRefreshing = false
        Toast.makeText(context, error, Toast.LENGTH_LONG).show()
    }

    override fun onClick(trip: Trip) {
        startActivity(
            Intent(context, TripDetailActivity::class.java).apply {
                putExtra(TripDetailActivity.TRIP, trip)
                // unfortunately, PTE does not save these locations reliably in the Trip object
                putExtra(TripDetailActivity.FROM, viewModel.fromLocation.value)
                putExtra(TripDetailActivity.VIA, viewModel.viaLocation.value)
                putExtra(TripDetailActivity.TO, viewModel.toLocation.value)
            }
        )
    }

    override fun onResume() {
        super.onResume()

        val lastUpdateTask = object : TimerTask() {
            @SuppressLint("SetTextI18n")
            override fun run() {
                secondsSinceUpdate += 1
                if (secondsSinceUpdate > 60) {
                    val minutes = secondsSinceUpdate / 60 // division rounding down
                    runOnUiThread { last_update_text.text = "Last updated ${minutes}m ago" }
                } else {
                    runOnUiThread { last_update_text.text = "Live updated" }
                }
            }
        }

        timer = Timer()
        timer.scheduleAtFixedRate(lastUpdateTask, 0, 1000)
    }

    override fun onPause() {
        super.onPause()
        timer.cancel()
        timer.purge()
    }

    companion object {
        val TAG = TripsFragment::class.java.name
    }
}