package com.example.lunchbox_offline_router

import android.annotation.SuppressLint
import android.location.Location
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.snackbar.Snackbar
import com.mapbox.android.core.permissions.PermissionsListener
import com.mapbox.android.core.permissions.PermissionsManager
import com.mapbox.api.directions.v5.models.DirectionsResponse
import com.mapbox.api.directions.v5.models.DirectionsRoute
import com.mapbox.geojson.BoundingBox
import com.mapbox.geojson.Point
import com.mapbox.mapboxsdk.Mapbox
import com.mapbox.mapboxsdk.camera.CameraPosition
import com.mapbox.mapboxsdk.camera.CameraUpdateFactory
import com.mapbox.mapboxsdk.geometry.LatLng
import com.mapbox.mapboxsdk.geometry.LatLngBounds
import com.mapbox.mapboxsdk.location.LocationComponentActivationOptions
import com.mapbox.mapboxsdk.location.LocationComponentOptions
import com.mapbox.mapboxsdk.location.modes.CameraMode
import com.mapbox.mapboxsdk.location.modes.RenderMode
import com.mapbox.mapboxsdk.maps.MapboxMap
import com.mapbox.mapboxsdk.maps.OnMapReadyCallback
import com.mapbox.mapboxsdk.maps.Style
import com.mapbox.mapboxsdk.offline.*
import com.mapbox.services.android.navigation.ui.v5.route.NavigationMapRoute
import com.mapbox.services.android.navigation.v5.milestone.Milestone
import com.mapbox.services.android.navigation.v5.milestone.MilestoneEventListener
import com.mapbox.services.android.navigation.v5.navigation.*
import com.mapbox.services.android.navigation.v5.offroute.OffRouteListener
import com.mapbox.services.android.navigation.v5.routeprogress.ProgressChangeListener
import com.mapbox.services.android.navigation.v5.routeprogress.RouteProgress
import kotlinx.android.synthetic.main.activity_main.*
import org.json.JSONObject
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.io.File

class MainActivity : AppCompatActivity(), OnMapReadyCallback, PermissionsListener, MapboxMap.OnMapClickListener,
    NavigationEventListener, ProgressChangeListener, OffRouteListener, MilestoneEventListener {


    private val MAPBOX_ACCESS_TOKEN = "ACCESS_TOKEN"
    private val mapbox_styleURL = "mapbox://styles/moritzz/ck57yl4f301r41cp2cehokywb"

    private lateinit var mapboxMap: MapboxMap

    private var permissionsManager: PermissionsManager = PermissionsManager(this)

    private var own_location: Point? = null
    private var destination: Point? = null

    private var navigationMapRoute: NavigationMapRoute? = null
    private var route: DirectionsRoute? = null

    // JSON encoding/decoding for offline region name
    val JSON_CHARSET: String = "UTF-8"
    val JSON_FIELD_REGION_NAME = "FIELD_REGION_NAME"

    // Offline Manager for offline Maps
    private lateinit var offlineManager: OfflineManager

    // Navigation
    private lateinit var navigation: MapboxNavigation
    private var running: Boolean = false
    private var tracking: Boolean = false

    // Offline Navigation
    private lateinit var offlineRouter: MapboxOfflineRouter
    private var configured_offline_route: Int = 0
    private lateinit var versioncode: String
    private lateinit var f: File
        // bounding box for offline download
    private var boundingBox: BoundingBox = BoundingBox.fromPoints(Point.fromLngLat(-7.834557, 61.3895), Point.fromLngLat(-6.0010, 62.4348))
        // progress bar offline download
    private var isEndNotified: Boolean? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.NavigationViewLight) //required for instructionView!!
        super.onCreate(savedInstanceState)
        Mapbox.getInstance(getApplicationContext(),  MAPBOX_ACCESS_TOKEN);
        setContentView(R.layout.activity_main)

        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync(this)

        //Navigation
        var nav_options: MapboxNavigationOptions = MapboxNavigationOptions.builder().isDebugLoggingEnabled(true).build();
        navigation = MapboxNavigation(getApplicationContext(), MAPBOX_ACCESS_TOKEN, nav_options);
        navigation.addNavigationEventListener(this);
        navigation.addMilestoneEventListener(this);

    }

    override fun onMapReady(mapboxMap: MapboxMap) {
        this.mapboxMap = mapboxMap
        mapboxMap.setStyle(
            Style.Builder().fromUri(
                mapbox_styleURL)) {
            // Enable LocationComponent
            enableLocationComponent(it)

            //add OnClickListener
            mapboxMap.addOnMapClickListener(this@MainActivity)

            //displaying a route on Mapview
            navigationMapRoute = NavigationMapRoute(null, mapView, mapboxMap);

            // ProgressBar to display Download progress
            progress_bar.visibility = View.VISIBLE


        }
    }

    override fun onMapClick(point: LatLng): Boolean {


        //get clicked coordinates
        destination = Point.fromLngLat(point.getLongitude(), point.getLatitude())


        // make route request
        build_nav_route()

        return true;
    }

    private fun build_nav_route(){

        NavigationRoute.builder(this)
            .accessToken(MAPBOX_ACCESS_TOKEN)
            .origin(own_location!!)
            .destination(destination!!)
            .build()
            .getRoute(object : Callback<DirectionsResponse> {
                override fun onResponse(call: Call<DirectionsResponse>, response: Response<DirectionsResponse>) {

                    if(response.isSuccessful && response.body() != null) {

                        try {
                            route = response.body()!!.routes().get(0) // Getting directionsRoute object for navigation

                            // displaying a route on MapView
                            var routes = response.body()!!.routes();
                            navigationMapRoute!!.addRoutes(routes);

                            //Start Navigation given a directionsRoute object
                            tracking = true // track device position
                            navigation.startNavigation(route!!)




                        } catch (e: Exception) {
                            Toast.makeText(this@MainActivity, "Invalid Route", Toast.LENGTH_LONG).show()
                        }
                    }else{
                        Log.e("Error", "Route Request: " + response.errorBody().toString())


                    }


                }

                override fun onFailure(call: Call<DirectionsResponse>, t: Throwable) {

                    Log.e("Failure", "Route Request error")

                    if(configured_offline_route == 1){
                        try {
                            createOfflineRoute()
                        }catch(e: Exception){
                            Log.e("MainActivity", "Offline router exception: " + e.message)

                        }
                    }else{
                        Toast.makeText(applicationContext,"Offline Router not configured.", Toast.LENGTH_LONG).show()
                        // Try to configure the offline Router
                        try {
                            configureOfflineRouter() // configure offline router. Offline Routing tiles should already be downloaded.
                        }catch(e: Exception){
                            Log.e("MainActivity", "Error configuring offline router: " + e.message)
                        }
                    }

                }
            })
    }


    @SuppressLint("MissingPermission")
    private fun enableLocationComponent(loadedMapStyle: Style) {
        // Check if permissions are enabled and if not request
        if (PermissionsManager.areLocationPermissionsGranted(this)) {

            // Create and customize the LocationComponent's options
            val customLocationComponentOptions = LocationComponentOptions.builder(this)
                .trackingGesturesManagement(true)
                .accuracyColor(
                    ContextCompat.getColor(this,
                        R.color.mapboxGreen
                    ))
                .build()

            val locationComponentActivationOptions = LocationComponentActivationOptions.builder(this, loadedMapStyle)
                .locationComponentOptions(customLocationComponentOptions)

                .build()

            // Get an instance of the LocationComponent and then adjust its settings
            mapboxMap.locationComponent.apply {

                // Activate the LocationComponent with options
                activateLocationComponent(locationComponentActivationOptions)

                // Enable to make the LocationComponent visible
                isLocationComponentEnabled = true

                // Set the LocationComponent's camera mode
                cameraMode = CameraMode.TRACKING

                // Set the LocationComponent's render mode
                renderMode = RenderMode.COMPASS

                own_location = Point.fromLngLat(mapboxMap.locationComponent.lastKnownLocation!!.longitude, mapboxMap.locationComponent.lastKnownLocation!!.latitude)


                // Kick off map tiles download
                init_offline_maps(loadedMapStyle.uri)

            }
        } else {
            permissionsManager = PermissionsManager(this)
            permissionsManager.requestLocationPermissions(this)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        permissionsManager.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    override fun onExplanationNeeded(permissionsToExplain: List<String>) {
        Toast.makeText(this, "Explanation", Toast.LENGTH_LONG).show()
    }

    override fun onPermissionResult(granted: Boolean) {
        if (granted) {
            enableLocationComponent(mapboxMap.style!!)
        } else {
            Toast.makeText(this, "User did not grant permission!", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    // Offline Maps -----------------------------------------

    private fun init_offline_maps(styleURL: String){

        // Set up the OfflineManager
        offlineManager = OfflineManager.getInstance(this@MainActivity)



        // Create a bounding box for the offline region
        val bounds: LatLngBounds = mapboxMap.getProjection().getVisibleRegion().latLngBounds
        val minZoom: Double = mapboxMap.getCameraPosition().zoom
        val maxZoom: Double = mapboxMap.getMaxZoomLevel()

        // Define the offline region
        val definition = OfflineTilePyramidRegionDefinition(
            styleURL,
            bounds,
            minZoom,
            maxZoom,
            this@MainActivity.getResources().getDisplayMetrics().density)

        // Define the name of the downloaded region
        var metadata: ByteArray?
        try {
            val jsonObject = JSONObject()
            jsonObject.put(JSON_FIELD_REGION_NAME, "Download region 1")
            val json = jsonObject.toString()
            metadata = json.toByteArray(charset(JSON_CHARSET))
        } catch (exception: Exception) {
            Log.e("MainActivity", "Failed to encode metadata: " + exception.message)
            metadata = null
        }

        if(definition != null && metadata != null){
            download_offline_map_tiles(definition, metadata)
        }

    }

    private fun download_offline_map_tiles(definition: OfflineTilePyramidRegionDefinition, metadata: ByteArray){

        // Create the region asynchronously
        offlineManager.createOfflineRegion(definition, metadata,
            object : OfflineManager.CreateOfflineRegionCallback {
                override fun onCreate(offlineRegion: OfflineRegion) {
                    offlineRegion.setDownloadState(OfflineRegion.STATE_ACTIVE)

                    // Monitor the download progress using setObserver
                    offlineRegion.setObserver(object : OfflineRegion.OfflineRegionObserver {
                        override fun onStatusChanged(status: OfflineRegionStatus) {

                            // Calculate the download percentage
                            val percentage = if (status.requiredResourceCount >= 0)
                                100.0 * status.completedResourceCount /status.requiredResourceCount else 0.0

                            // update ProgressBar with current percentage
                            progress_bar.setProgress(percentage.toInt())

                            if (status.isComplete) {
                                // Download complete
                                Log.d("MainActivity", "Region downloaded successfully.")
                                // ProgressBar to display Download progress
                                progress_bar.visibility = View.INVISIBLE

                                // Kick off download of offline routing tiles
                                init_offline_routing_tiles()

                            } else if (status.isRequiredResourceCountPrecise) {
                                Log.d("MainActivity", percentage.toString())
                            }





                        }

                        override fun onError(error: OfflineRegionError) {
                            // If an error occurs, print to logcat
                            Log.e("MainActivity", "onError reason: " + error.reason)
                            Log.e("MainActivity", "onError message: " + error.message)
                        }

                        override fun mapboxTileCountLimitExceeded(limit: Long) {
                            // Notify if offline region exceeds maximum tile count
                            Log.e("MainActivity", "Mapbox tile count limit exceeded: $limit")
                        }
                    })
                }

                override fun onError(error: String) {
                    Log.e("MainActivity", "Error: $error")
                }
            })
    }

    override fun onRunning(running: Boolean) {
        this.running = running;
        if (running) {
            navigation.addOffRouteListener(this);
            navigation.addProgressChangeListener(this);
        }
    }

    override fun onProgressChange(location: Location, routeProgress: RouteProgress) {

        if (tracking != null) {//centers cameraposition on updated location
            mapboxMap.getLocationComponent().forceLocationUpdate(location);
            var cameraPosition: CameraPosition = CameraPosition.Builder()
                .zoom(15.0)
                .target(LatLng(location.getLatitude(), location.getLongitude()))
                .bearing(location.getBearing().toDouble())
                .build();
            mapboxMap.animateCamera(CameraUpdateFactory.newCameraPosition(cameraPosition), 2000);
        }
        instructionView.updateDistanceWith(routeProgress);

    }

    override fun onMilestoneEvent(routeProgress: RouteProgress , instruction: String , milestone: Milestone) {
        instructionView.updateBannerInstructionsWith(milestone);
    }


    override fun userOffRoute(location: Location ) {

        own_location = Point.fromLngLat(location.getLongitude(), location.getLatitude());

        // Flag that device is off route
        Snackbar.make(progress_bar, "Device off-route. Re-routing..", Snackbar.LENGTH_SHORT).show();

        //recalculate route and start navigation
        navigation.stopNavigation()

        // build route and re-start navigation
        build_nav_route()
    }


    // Initiate the offline routing tiles download
    private fun init_offline_routing_tiles(){

        // create folder to store offline routing tiles
        val folder_main = "Offline_Navigation"
        val f = File(
            applicationContext.filesDir,
            folder_main
        )
        if (!f.exists()) {
            f.mkdirs()
        }
        offlineRouter = MapboxOfflineRouter(f.toPath().toString())

        offlineRouter.fetchAvailableTileVersions(MAPBOX_ACCESS_TOKEN, object:
            OnTileVersionsFoundCallback {
            override fun onVersionsFound(availableVersions: List<String>) {
                // Choose the latest version
                versioncode = availableVersions[availableVersions.count() - 1 ]

                // Start offline routing tiles download
                downloadtiles(versioncode)

            }

            override fun onError(error: OfflineError) {
                Toast.makeText(applicationContext, "Unable to download tiles" + error.message, Toast.LENGTH_LONG).show()

            }
        })


    }


    // Downloading offline routing tiles
    private fun downloadtiles(versionString: String){

        val builder = OfflineTiles.builder()
            .accessToken(MAPBOX_ACCESS_TOKEN)
            .version(versionString)
            .boundingBox(boundingBox)

        // Display the download progress bar
        progress_bar.visibility  = View.VISIBLE

        //start download
        offlineRouter.downloadTiles(builder.build(), object : RouteTileDownloadListener {



            override fun onError(error: OfflineError) {
                // Will trigger if an error occurs during the download
                // Show a toast
                Toast.makeText(this@MainActivity, "Error downloading nav data" + error.message, Toast.LENGTH_LONG).show();
                // hide the download progress bar
                progress_bar.visibility  = View.INVISIBLE
            }

            override fun onProgressUpdate(percent: Int) {
                // Update progress bar
                progress_bar.setProgress(percent, true)

                Log.d("download percent", percent.toString())


            }

            override fun onCompletion() {
                // hide the download progress bar
                progress_bar.visibility  = View.INVISIBLE


                try {
                    configureOfflineRouter() // configure offline router. Offline Routing tiles should already be downloaded.
                }catch(e: Exception){
                    Log.e("MainActivity", "Error configuring offline router: " + e.message)
                }


            }
        })

    }


    // Configuring the OfflineRouter

    private fun configureOfflineRouter(){

        // create folder to store offline routing tiles
        val folder_main = "Offline_Navigation"
        val f = File(
            applicationContext.filesDir,
            folder_main
        )
        if (!f.exists()) {
            f.mkdirs()
        }

        // versioncode == folder name 1 level below f
        var files = f.listFiles() //is file list
        for (item in files) {
            if (item.isDirectory()) {

                var files2 = item.listFiles()
                for (item2 in files2) {
                    if (item2.isDirectory()) {
                        versioncode = item2.name
                    }

                }
            }
        }

        offlineRouter.configure(versioncode, object : OnOfflineTilesConfiguredCallback {

            override fun onConfigured(numberOfTiles: Int) {
                Log.d("MainActivity", ("Offline tiles configured:" + numberOfTiles ))
                // Ready
                configured_offline_route = 1


            }

            override fun onConfigurationError(error: OfflineError) {
                Log.e("Embed nav", ("Offline tiles configuration error:"  + error.message))
                // Not ready
                configured_offline_route = 0
            }
        }

        )



    }

    private fun createOfflineRoute(){

        val onlineRouteBuilder = NavigationRoute.builder(this)
            .origin(own_location!!)
            .destination(destination!!)
            .accessToken(MAPBOX_ACCESS_TOKEN)



        val offlineRoute = OfflineRoute.builder(onlineRouteBuilder).build()
        offlineRouter.findRoute(offlineRoute, object : OnOfflineRouteFoundCallback {
            override fun onRouteFound(route: DirectionsRoute) {
                // Start navigation with route

                try {
                    navigationMapRoute!!.addRoute(route);
                    tracking = true
                    navigation.startNavigation(route)

                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "Invalid Route: " + e.message, Toast.LENGTH_LONG).show()
                }

            }

            override fun onError(error: OfflineError) {

                Toast.makeText(this@MainActivity, "Error find route offline: " + error.message, Toast.LENGTH_LONG).show()
                // Handle route error
            }

        })

    }


}
