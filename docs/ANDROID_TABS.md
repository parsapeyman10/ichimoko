# Android tab map

The Compose UI keeps navigation, tab state, and tab presentation separate so a change to one tab does not require reading the entire app.

## Navigation shell

- `ui/AurumRoot.kt`: lifecycle, snackbar, bottom navigation, and dispatch to a selected tab only.
- `ui/TabNavigation.kt`: tab names, labels, icons, and primary/more grouping.
- `ui/AppHeader.kt`: shared market header used by Chart and Signal.

## Tab entry points

- `ui/HomeScreen.kt`: home dashboard.
- `ui/ChartScreen.kt`: live chart.
- `ui/SignalScreen.kt`: signal and paper-entry view.
- `ui/MarketWatchScreen.kt`: watch list and historical watch observations.
- `ui/PersianNewsScreen.kt`: news and calendar.
- `ui/LearnScreen.kt`: research inputs and controls.
- `ui/LearnReports.kt`: backtest and walk-forward report cards.
- `ui/ReplayPanel.kt`: bar replay controls and interactive paper-test results.
- `ui/JournalScreen.kt`: paper journal.
- `ui/UpdateScreen.kt`: update discovery and Package Installer handoff.
- `ui/SettingsScreen.kt` and `ui/ApiMenuScreen.kt`: device, provider, and AI settings.

## Tab-owned state models

- `ui/LearnUiState.kt`: research, walk-forward, and replay state.
- `ui/WatchUiState.kt`: paginated watch history state.
- `ui/JournalUiState.kt`: expanded journal trade-chart state.
- `ui/AiUiState.kt`: AI probe and model catalogue state.

`AurumViewModel.kt` remains the single application coordinator and strategy/data owner. The state types are split into focused files, while business logic stays centralized so tabs cannot accidentally create duplicate strategy engines or bypass paper-only rules.
