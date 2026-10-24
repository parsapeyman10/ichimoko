# Android tab map

The Compose UI keeps navigation, tab state, and tab presentation separate so a change to one tab does not require reading the entire app.

## Navigation shell

- `ui/shell/AurumRoot.kt`: lifecycle, snackbar, bottom navigation, and dispatch to a selected tab only.
- `ui/shell/TabNavigation.kt`: tab names, labels, icons, and primary/more grouping.
- `ui/shell/AurumViewModel.kt`: application coordinator only; it does not contain tab UI.

## Tab entry points

- `ui/tabs/home/HomeScreen.kt`: home dashboard.
- `ui/tabs/chart/ChartScreen.kt` and `TradingViewEmbed.kt`: one full-tab TradingView Advanced Chart widget with its own controls; app feed and trade rules are not part of the chart.
- `ui/tabs/signal/SignalScreen.kt` and `PaperTicketSection.kt`: signal and paper-entry view.
- `ui/tabs/signal/Als5OpportunityMonitorCard.kt`, `SilverAresEnginesCard.kt`, `BtcAresEnginesCard.kt` and `EurAresEnginesCard.kt`: one card per engine, each reporting its own conditions, costs and refusals.
- `ui/tabs/watch/MarketWatchScreen.kt`: watch list and historical watch observations.
- `ui/tabs/journal/JournalScreen.kt` and `TradeReplayChart.kt`: paper journal and the per-trade replay chart.
- `ui/tabs/update/UpdateScreen.kt`: update discovery and Package Installer handoff.
- `ui/tabs/settings/SettingsScreen.kt` and `ApiMenuScreen.kt`: device, provider, and API settings.

## Tab-owned state models

- `ui/tabs/journal/JournalUiState.kt`: expanded journal trade-chart state.

Shared visual pieces live under `ui/shared` and `ui/components`; strategy, data, and persistence remain outside the UI tree.


`AurumViewModel.kt` remains the single application coordinator and strategy/data owner. The state types are split into focused files, while business logic stays centralized so tabs cannot accidentally create duplicate strategy engines or bypass paper-only rules.
