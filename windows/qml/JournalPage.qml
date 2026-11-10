import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import AurumEdge

Page {
    background: Rectangle { color: Theme.background }
    contentItem: ColumnLayout {
        anchors.fill: parent
        anchors.margins: 20
        spacing: 12
        Label { text: "ژورنال واقعی — SQLite محلی"; color: Theme.text; font.pixelSize: 23; font.bold: true }
        Frame {
            Layout.fillWidth: true
            background: Rectangle { color: Theme.panel; border.color: Theme.border; radius: 8 }
            contentItem: GridLayout {
                columns: 7
                rowSpacing: 10
                columnSpacing: 8
                Label { text: "نماد"; color: Theme.muted }
                Label { text: "جهت"; color: Theme.muted }
                Label { text: "ورود"; color: Theme.muted }
                Label { text: "SL"; color: Theme.muted }
                Label { text: "TP"; color: Theme.muted }
                Label { text: "خروج"; color: Theme.muted }
                Label { text: "PnL"; color: Theme.muted }
                TextField { id: symbol; text: "BTC/USD"; color: Theme.text; Layout.fillWidth: true }
                ComboBox { id: side; model: ["BUY", "SELL"]; Layout.fillWidth: true }
                TextField { id: entry; placeholderText: "100000"; color: Theme.text; inputMethodHints: Qt.ImhFormattedNumbersOnly; Layout.fillWidth: true }
                TextField { id: stop; placeholderText: "99000"; color: Theme.text; inputMethodHints: Qt.ImhFormattedNumbersOnly; Layout.fillWidth: true }
                TextField { id: target; placeholderText: "102000"; color: Theme.text; inputMethodHints: Qt.ImhFormattedNumbersOnly; Layout.fillWidth: true }
                TextField { id: exitPrice; placeholderText: "102000"; color: Theme.text; inputMethodHints: Qt.ImhFormattedNumbersOnly; Layout.fillWidth: true }
                TextField { id: pnl; placeholderText: "20"; color: Theme.text; inputMethodHints: Qt.ImhFormattedNumbersOnly; Layout.fillWidth: true }
                Button {
                    Layout.columnSpan: 7
                    Layout.fillWidth: true
                    text: "ثبت معامله"
                    enabled: Number(entry.text) > 0 && Number(exitPrice.text) > 0
                    onClicked: if (App.addJournalTrade(symbol.text, side.currentText, Number(entry.text), Number(stop.text), Number(target.text), Number(exitPrice.text), Number(pnl.text))) { entry.clear(); stop.clear(); target.clear(); exitPrice.clear(); pnl.clear() }
                }
            }
        }
        Frame {
            Layout.fillWidth: true
            Layout.fillHeight: true
            background: Rectangle { color: Theme.panel; border.color: Theme.border }
            contentItem: ListView {
                anchors.fill: parent
                clip: true
                model: App.journalTrades
                delegate: Rectangle {
                    required property var modelData
                    width: ListView.view.width
                    height: 42
                    color: index % 2 ? Theme.panel : Theme.panelAlt
                    RowLayout {
                        anchors.fill: parent; anchors.margins: 10
                        Label { text: modelData.symbol; color: Theme.text; Layout.fillWidth: true }
                        Label { text: modelData.side; color: modelData.side === "BUY" ? Theme.green : Theme.red }
                        Label { text: Number(modelData.entry).toFixed(5); color: Theme.muted; Layout.fillWidth: true }
                        Label { text: Number(modelData.exit).toFixed(5); color: Theme.muted; Layout.fillWidth: true }
                        Label { text: "$" + Number(modelData.netPnl).toFixed(2); color: modelData.netPnl >= 0 ? Theme.green : Theme.red }
                    }
                }
                Label { anchors.centerIn: parent; visible: App.journalTrades.length === 0; text: "ژورنال خالی است"; color: Theme.muted }
            }
        }
    }
}
