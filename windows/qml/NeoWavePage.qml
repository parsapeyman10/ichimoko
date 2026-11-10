import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import QtQuick.Dialogs
import AurumEdge

Page {
    background: Rectangle { color: Theme.background }
    FileDialog { id: fileDialog; nameFilters: ["CSV files (*.csv)"]; onAccepted: App.loadCsv(selectedFile.toString()) }
    contentItem: ColumnLayout {
        anchors.fill: parent; anchors.margins: 22; spacing: 14
        RowLayout {
            Label { text: "تحلیل ساختاری NeoWave"; color: Theme.text; font.pixelSize: 24; font.bold: true; Layout.fillWidth: true }
            Button { text: App.hasData ? "تغییر CSV" : "انتخاب CSV"; enabled: !App.busy; onClicked: fileDialog.open() }
        }
        Label { text: "الگوها از حداکثر ۲۴۰ کندل بسته و پیوت‌های ATR ساخته می‌شوند؛ نبود ساختار معتبر، خنثی است و سیگنال ساختگی تولید نمی‌کند."; color: Theme.muted; wrapMode: Text.WordWrap; Layout.fillWidth: true }
        Frame {
            Layout.fillWidth: true
            background: Rectangle { color: Theme.panel; border.color: Theme.border; radius: 8 }
            contentItem: Label { text: App.hasData ? App.neoWaveSummary : "برای شمارش واقعی ابتدا CSV انتخاب کنید."; color: Theme.accent; wrapMode: Text.WordWrap; width: parent.width }
        }
        Frame {
            Layout.fillWidth: true; Layout.fillHeight: true
            background: Rectangle { color: Theme.panel; border.color: Theme.border }
            contentItem: ListView {
                anchors.fill: parent; clip: true; model: App.neoWavePivots
                header: Label { text: "آخرین پیوت‌های تأییدشده"; color: Theme.text; padding: 12; font.bold: true }
                delegate: Rectangle {
                    required property var modelData
                    width: ListView.view.width; height: 38; color: index % 2 ? Theme.panel : Theme.panelAlt
                    RowLayout {
                        anchors.fill: parent; anchors.margins: 10
                        Label { text: "#" + modelData.index; color: Theme.muted }
                        Label { text: modelData.type; color: modelData.type === "HIGH" ? Theme.red : Theme.green; Layout.fillWidth: true }
                        Label { text: Number(modelData.price).toFixed(6); color: Theme.text }
                    }
                }
                Label { anchors.centerIn: parent; visible: App.neoWavePivots.length === 0; text: "پیوت کافی وجود ندارد"; color: Theme.muted }
            }
        }
    }
}
