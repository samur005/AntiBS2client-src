# Home-screen map and globe (1.0.46)

* `app/src/main/assets/world_map_paths.txt` — world land outline (one SVG path per line, viewport 1024×430,
  equirectangular, latitude 91.75 … −58.22). It is `world_map_clean.xml` from **NetGuard** by KOKosaaaa
  (<https://github.com/KOKosaaaa/NetGuard>), licensed under the **Apache License 2.0**
  (full text: `third_party/NetGuard-Apache-2.0-LICENSE.txt`), extracted to a path list. The geometry itself derives from Natural Earth 1:110m land (public domain).
  The drawing style of the flat map (calibrated equirectangular projection, translucent land, dashed user → server
  arc that grows on connect, pulsing server marker) follows NetGuard's ConnectionMapView; the Compose
  implementation in `ui/WorldView.kt` was written for AntiBS2client.
* `app/src/main/assets/land_mask.bin` — 2048×1024 1-bit land mask for the globe, rasterised from
  Natural Earth 1:50m land (public domain, "Made with Natural Earth", <https://www.naturalearthdata.com/>).
* `data/GeoCountries.kt` — country label points from Natural Earth 1:50m admin-0 countries (public domain).
* IP geolocation (optional, can be turned off in the view): ipwho.is, freeipapi.com, ipapi.co public endpoints.
