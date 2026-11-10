import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import QtQuick.Dialogs
import AurumEdge

Page {
    background: Rectangle { color: Theme.background }
    FileDialog {
        id: fileDialog
        nameFilters: ["CSV files (*.csv)"]
        onAccepted: App.loadCsv(selectedFile.toString())
    }
    contentItem: ColumnLayout {
        anchors.fill: parent
        anchors.margins: 20
        spacing: 12
        RowLayout {
            Button { text: App.hasData ? "تغییر CSV" : "انتخاب CSV"; enabled: !App.busy; onClicked: fileDialog.open() }
            Button { text: "اجرای بک‌تست"; enabled: App.hasData && !App.busy; onClicked: App.runBacktest() }
            ProgressBar { indeterminate: App.busy; visible: App.busy; Layout.fillWidth: true }
        }
        Label { text: App.modelName; color: Theme.muted; wrapMode: Text.WordWrap; Layout.fillWidth: true }
        Label { text: App.resultSummary.length ? App.resultSummary : "پس از انتخاب داده، بک‌تست را اجرا کنید"; color: Theme.text; font.pixelSize: 18; wrapMode: Text.WordWrap; Layout.fillWidth: true }
        Label { visible: App.neuralSummary.length > 0; text: App.neuralSummary; color: Theme.accent; wrapMode: Text.WordWrap; Layout.fillWidth: true }
        Frame {
            Layout.fillWidth: true
            Layout.fillHeight: true
            background: Rectangle { color: Theme.panel; border.color: Theme.border }
            contentItem: ListView {
                anchors.fill: parent
                clip: true
                model: App.backtestTrades
                header: Label { text: "دفتر معاملات بک‌تست"; color: Theme.accent; font.bold: true; padding: 12 }
                delegate: Rectangle {
                    required property var modelData
                    width: ListView.view.width
                    height: 44
                    color: index % 2 ? Theme.panel : Theme.panelAlt
                    RowLayout {
                        anchors.fill: parent
                        anchors.margins: 10
                        Label { text: modelData.side; color: modelData.side === "BUY" ? Theme.green : Theme.red; Layout.preferredWidth: 55 }
                        Label { text: "ورود " + Number(modelData.entry).toFixed(5); color: Theme.text; Layout.fillWidth: true }
                        Label { text: "خروج " + Number(modelData.exit).toFixed(5); color: Theme.text; Layout.fillWidth: true }
                        Label { text: Number(modelData.r).toFixed(2) + "R"; color: modelData.netPnl >= 0 ? Theme.green : Theme.red }
                        Label { text: "$" + Number(modelData.netPnl).toFixed(2); color: modelData.netPnl >= 0 ? Theme.green : Theme.red; Layout.preferredWidth: 90 }
                    }
                }
                Label { anchors.centerIn: parent; visible: App.backtestTrades.length === 0; text: "معامله‌ای ثبت نشده"; color: Theme.muted }
            }
        }
    }
}
