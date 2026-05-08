## Location Tracking Modes
* **Mode 0 (Pure GPS)**: Uses standard `LocationManager.GPS_PROVIDER`. Highest accuracy but highest battery consumption.
* **Mode 1 (Pure Network)**: Uses standard `LocationManager.NETWORK_PROVIDER`. Lower power consumption, relies on cell towers and Wi-Fi networks for position.
* **Mode 2 (Google Balanced Power)**: Uses `FusedLocationProviderClient` with `PRIORITY_BALANCED_POWER_ACCURACY`. Optimizes between power consumption and accuracy automatically.
* **Mode 3 (Google Smart Passive)**: Uses `PRIORITY_PASSIVE` to opportunistically grab locations requested by other apps (e.g., Google Maps) for near-zero battery drain. Falls back to making an active high-accuracy request if the cached location gets too old or inaccurate based on user-defined thresholds.
