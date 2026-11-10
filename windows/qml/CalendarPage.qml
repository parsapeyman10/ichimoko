import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import AurumEdge

Page {
    id: page
    property int year: new Date().getFullYear()
    property int month: new Date().getMonth() + 1
    property var calendar: ({})
    function refresh() { calendar = App.monthlyCalendar(year, month) }
    function moveMonth(delta) {
        month += delta
        if (month < 1) { month = 12; year-- }
        if (month > 12) { month = 1; year++ }
        refresh()
    }
    Component.onCompleted: refresh()
    Connections { target: App; function onJournalChanged() { page.refresh() } }
    background: Rectangle { color: Theme.background }
    contentItem: ColumnLayout {
        anchors.fill: parent; anchors.margins: 22; spacing: 14
        RowLayout {
            Label { text: "تقویم عملکرد ماهانه"; color: Theme.text; font.pixelSize: 24; font.bold: true; Layout.fillWidth: true }
            Label { text: year + " / " + month; color: Theme.accent; font.pixelSize: 18 }
            Button { text: "ماه قبل"; onClicked: moveMonth(-1) }
            Button { text: "ماه بعد"; onClicked: moveMonth(1) }
        }
        RowLayout {
            Layout.fillWidth: true; spacing: 18
            Label { text: "PnL: $" + Number(calendar.net || 0).toFixed(2); color: (calendar.net || 0) >= 0 ? Theme.green : Theme.red }
            Label { text: "برد/باخت: " + (calendar.wins || 0) + " / " + (calendar.losses || 0); color: Theme.text }
            Label { text: "Win rate: " + Number(calendar.winRate || 0).toFixed(1) + "%"; color: Theme.text }
            Label { text: "روز سبز/قرمز: " + (calendar.winningDays || 0) + " / " + (calendar.losingDays || 0); color: Theme.muted }
        }
        Frame {
            Layout.fillWidth: true; Layout.fillHeight: true
            background: Rectangle { color: Theme.panel; border.color: Theme.border; radius: 8 }
            contentItem: GridLayout {
                columns: 7; rows: 7; rowSpacing: 6; columnSpacing: 6
                Repeater { model: ["شنبه", "یکشنبه", "دوشنبه", "سه‌شنبه", "چهارشنبه", "پنجشنبه", "جمعه"]
                    Label { required property string modelData; text: modelData; color: Theme.muted; horizontalAlignment: Text.AlignHCenter; Layout.fillWidth: true } }
                Repeater { model: calendar.offset || 0; Item { Layout.preferredHeight: 64; Layout.fillWidth: true } }
                Repeater {
                    model: calendar.days || []
                    delegate: Rectangle {
                        required property var modelData
                        Layout.fillWidth: true; Layout.fillHeight: true; Layout.minimumHeight: 64
                        radius: 5
                        color: modelData.count === 0 ? Theme.panelAlt : modelData.net > 0 ? "#153d36" : modelData.net < 0 ? "#47232d" : Theme.panelAlt
                        border.color: Theme.border
                        Column {
                            anchors.centerIn: parent
                            Label { text: modelData.day; color: Theme.text; anchors.horizontalCenter: parent.horizontalCenter }
                            Label { visible: modelData.count > 0; text: "$" + Number(modelData.net).toFixed(2); color: modelData.net >= 0 ? Theme.green : Theme.red; anchors.horizontalCenter: parent.horizontalCenter }
                            Label { visible: modelData.count > 0; text: modelData.count + " معامله"; color: Theme.muted; font.pixelSize: 11; anchors.horizontalCenter: parent.horizontalCenter }
                        }
                    }
                }
            }
        }
    }
}
