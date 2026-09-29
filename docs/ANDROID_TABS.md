# Android tab map

The Compose UI keeps navigation, tab state, and tab presentation separate so a change to one tab does not require reading the entire app.

## Navigation shell

- `ui/shell/AurumRoot.kt`: lifecycle, snackbar, bottom navigation, and dispatch to a selected tab only.
- `ui/shell/TabNavigation.kt`: tab names, labels, icons, and primary/more grouping.
- `ui/shell/AppHeader.kt`: shared market header used by Chart and Signal.
- `ui/shell/AurumViewModel.kt`: application coordinator only; it does not contain tab UI.

## Tab entry points

- `ui/tabs/home/HomeScreen.kt`: home dashboard.
- `ui/tabs/chart/ChartScreen.kt` and `CandleChart.kt`: live chart.
- `ui/tabs/signal/SignalScreen.kt` and `PaperTicketSection.kt`: signal and paper-entry view.
- `ui/tabs/watch/MarketWatchScreen.kt`: watch list and historical watch observations.
- `ui/tabs/news/PersianNewsScreen.kt`: news and calendar.
- `ui/tabs/learn/LearnScreen.kt`: research inputs and controls.
- `ui/tabs/learn/LearnReports.kt`: backtest and walk-forward report cards.
- `ui/tabs/learn/ReplayPanel.kt`: bar replay controls and interactive paper-test results.
- `ui/tabs/journal/JournalScreen.kt`: paper journal.
- `ui/tabs/update/UpdateScreen.kt`: update discovery and Package Installer handoff.
- `ui/tabs/settings/SettingsScreen.kt` and `ApiMenuScreen.kt`: device, provider, and AI settings.

## Tab-owned state models

- `ui/tabs/learn/LearnUiState.kt`: research, walk-forward, and replay state.
- `ui/tabs/watch/WatchUiState.kt`: paginated watch history state.
- `ui/tabs/journal/JournalUiState.kt`: expanded journal trade-chart state.
- `ui/tabs/settings/AiUiState.kt`: AI probe and model catalogue state.

Shared visual pieces live under `ui/shared` and `ui/components`; strategy, data, and persistence remain outside the UI tree.


`AurumViewModel.kt` remains the single application coordinator and strategy/data owner. The state types are split into focused files, while business logic stays centralized so tabs cannot accidentally create duplicate strategy engines or bypass paper-only rules.
