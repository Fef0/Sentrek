# Sentrek

A hiking map for Android that keeps working when the signal is gone, the battery is low and your hands are cold.

Download the areas you need, plan a route that sticks to real trails, and follow it. Along the way, Sentrek tells you what matters: how far and how high is left, when you'll get there, and whether that's before dark.

<table>
  <tr>
    <td align="center"><img src="docs/media/map.gif" width="200" alt="Moving around the hiking map"><br><b>A map made for walking</b></td>
    <td align="center"><img src="docs/media/plan.gif" width="200" alt="Planning a route that follows the trails"><br><b>Plan along the trails</b></td>
    <td align="center"><img src="docs/media/download.gif" width="200" alt="Downloading an area for offline use"><br><b>Download an area</b></td>
    <td align="center"><img src="docs/media/follow.gif" width="200" alt="Following a route with the trail card"><br><b>Follow a route</b></td>
  </tr>
</table>

## What it does

### Maps that work offline
- Tap squares on a grid to download them. Each one is about 55 × 55 km and takes around 30 MB.
- Hiking trails, waymarked routes with their painted signs, huts, springs, peaks and viewpoints.
- Contour lines and hill shading, both worked out on the phone from terrain data.
- Optional colours for steep slopes (avalanche-map style) and for trail difficulty on the SAC scale.
- You can also open your own `.mbtiles` and `.pmtiles` files.

### Route planner
- Tap where you want to go. The route follows paths and tracks from the map data, and it works offline in downloaded areas.
- Distance, climb, descent and walking time update as you add points, along with the elevation profile.
- Drag points to move them, drag a midpoint to add one, and undo or redo as much as you like.
- Save the route, or export it as GPX or KML.

### Following a route
- The trail card shows three big numbers: distance left, climb left and time left. After a while the time is based on your own pace.
- Tap the card to see the places ahead (huts, springs, peaks), how far each one is, and the elevation profile with your position on it.
- The phone vibrates when you leave the route and again when you're back on it.
- Import GPX, KML and KMZ files, or open them from another app with "Open with Sentrek".

### Recording
- Records in the background with the screen off, with a live notification on the lock screen.
- Pause and resume. Add places and photos along the way.
- Your recorded tracks add up to walking totals for this year and for all time.

### Warnings before they matter
These show up only when there's something to say:
- **Sunset:** if you'd reach the end of the route after dark, and a countdown in the last hours of daylight.
- **Battery:** if it looks like it'll run out before you're done, with a one-tap switch to stamina mode.
- **Rain, snow and storms:** where you'll be, from the forecast.
- **Water:** the last spring or fountain before a long dry stretch.
- **Sun:** long stretches without shade, worked out from the terrain and the woods on the map.
- **No signal:** where your phone last had signal, with "Take me there".
- A turnaround alarm, at a time you choose or when the light won't last.

### Made for bad conditions
- **Glove mode:** the biggest buttons, long presses that are easier to land, and zoom with the volume keys.
- A lock button, so rain and pockets can't press anything.
- Large text, dark and black themes, a night map, and full screen.
- **Battery saver** and **stamina mode** for long days. Stamina mode can switch itself on at a battery level you choose.

### Safety
- An SOS screen with your position in big text, as coordinates, UTM and Plus Code, with buttons to call or text for help.
- It can text your position to someone every few minutes while you walk. This uses plain SMS, so it works without mobile data.
- "Peaks around" names the summits you can see from where you stand.

## What it doesn't do

- No account, no ads, no analytics and no tracking.
- No Google Play Services, so it runs on phones without them.

Your tracks, routes and places stay on your phone. You can back them up to a file whenever you like.

The app goes online only for:
- map and terrain tiles, when you browse or download an area
- trail details (difficulty, huts, springs) for downloaded areas
- the weather forecast, if you turn it on

## Install

Sentrek needs Android 7.0 or newer.

Get the APK from the [Releases](../../releases) page:
- `Sentrek-x.y.z-arm64.apk` is right for almost every phone from the last ten years.
- `Sentrek-x.y.z-universal.apk` works on any device, but it's a bigger download.

## Build it yourself

See [BUILDING.md](BUILDING.md). In short, with JDK 17+ and the Android SDK installed:

```sh
git clone <this repository>
cd Sentrek
./gradlew assembleDebug
```

## Where the data comes from

- Map data © [OpenStreetMap](https://www.openstreetmap.org/copyright) contributors, available under the Open Database License.
- Vector tiles from [OpenFreeMap](https://openfreemap.org/), using the [OpenMapTiles](https://openmaptiles.org/) schema.
- Terrain from the Mapzen terrain tiles, hosted on [AWS Open Data](https://registry.opendata.aws/terrain-tiles/).
- Trail details from the [Overpass API](https://overpass-api.de/) (FOSSGIS, VK Maps, private.coffee).
- Weather from [Open-Meteo](https://open-meteo.com/).
- Map icons from [OpenStreetMap Carto](https://github.com/gravitystorm/openstreetmap-carto) (CC0).

The maps are drawn with [MapLibre Native](https://github.com/maplibre/maplibre-native).

## Contributing

Bug reports and ideas are welcome in the issues. Please include your phone model, Android version and the Sentrek version from Settings › About. For anything bigger than a small fix, open an issue first so we can agree on the approach.

## License

Sentrek is free software, released under the [GNU General Public License v3.0](LICENSE).
