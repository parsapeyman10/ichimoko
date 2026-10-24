# Android tab map

The Compose UI contains only three tabs:

## Navigation shell

- `ui/shell/AurumRoot.kt`: lifecycle, snackbar, bottom navigation, and dispatch to the selected tab.
- `ui/shell/TabNavigation.kt`: the three tabs (`Home` / خانه, `Chart` / چارت, `Update` / بروزرسانی).
- `ui/shell/AurumViewModel.kt`: application coordinator for Home, Chart, and Update.

## Tab entry points

- `ui/tabs/home/HomeScreen.kt`: complete home screen.
- `ui/tabs/chart/ChartScreen.kt` and `TradingViewEmbed.kt`: complete TradingView chart screen.
- `ui/tabs/update/UpdateScreen.kt`: complete update discovery and Package Installer handoff screen.
