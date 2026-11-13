import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import QtQuick.Dialogs
import AurumEdge

Page {
    background: Rectangle { color: Theme.background }
    FileDialog {
        id: fileDialog
        title: "انتخاب Dukascopy CSV"
        nameFilters: ["CSV files (*.csv)", "All files (*)"]
        onAccepted: App.loadCsv(selectedFile.toString())
    }
    contentItem: ColumnLayout {
        anchors.fill: parent
        anchors.margins: 18
        spacing: 12
        RowLayout {
            Button { text: "انتخاب CSV"; enabled: !App.busy; onClicked: fileDialog.open() }
            Label { text: App.fileName; color: Theme.text; elide: Label.ElideLeft; Layout.fillWidth: true }
            Label { text: App.candleCount ? App.candleCount.toLocaleString() + " کندل" : "بدون داده"; color: Theme.muted }
        }
        RowLayout {
            visible: App.hasData
            TextField { id: archivePath; text: "candles.csv"; color: Theme.text; Layout.fillWidth: true; placeholderText: "مسیر Archive کندل" }
            Button { text: "Archive CSV"; onClicked: { archivePath.text = archivePath.text.replace(/\.json$/i, ".csv"); App.exportCandles(archivePath.text) } }
            Button { text: "Archive JSON"; onClicked: { archivePath.text = archivePath.text.replace(/\.csv$/i, ".json"); App.exportCandles(archivePath.text) } }
        }
        CandleChart {
            id: chart
            Layout.fillWidth: true
            Layout.fillHeight: true
            candles: App.chartCandles
        }
        Label { text: "حداکثر ۱۵۰۰ کندل آخر برای رسم · محاسبات روی تمام داده انجام می‌شود"; color: Theme.muted; font.pixelSize: 12 }
    }
}
