# Offline Routing

<img src="https://github.com/moritzzzzz/offline_navigation/blob/master/lunchbox6.gif" width="200">

This is a basic offline routing Android application written in Kotlin. It utilizes the [Mapbox Maps SDK for Android](https://docs.mapbox.com/android/maps/overview/) as well as the [Mapbox Navigation SDK for Android](https://docs.mapbox.com/android/navigation/overview/).

**Please note: To access offline routing tiles an enterprise subscription must be in place. Developer plan subscriptions do not offer access to offline routing tiles. Please get in touch with Mapbox sales to discuss access.**

## How to get this application to run on your device

First off, make sure to have a Mapbox account. There are 2 files that are missing an access token.

 - Project level build.gradle: Please follow these instructions to add the secret token: https://docs.mapbox.com/android/maps/overview/#add-the-dependency
 - MainActivity.kt: Please set the value of 'MAPBOX_ACCESS_TOKEN' to a valid token.
 - MainActivity.kt: Please set the value of 'mapbox_styleURL' to a valid style URL.

Once these have been edited the project can be compiled.



