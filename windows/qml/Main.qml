import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import AurumEdge

ApplicationWindow {
    id: window
    width: 1440
    height: 900
    minimumWidth: 980
    minimumHeight: 640
    visible: true
    title: "Aurum Edge — Windows x64"
    color: Theme.background
    LayoutMirroring.enabled: true
    LayoutMirroring.childrenInherit: true

    header: ToolBar {
        background: Rectangle { color: Theme.panel }
        contentItem: RowLayout {
            spacing: 18
            Label { text: "AURUM EDGE"; color: Theme.accent; font.bold: true; font.pixelSize: 20; Layout.leftMargin: 20 }
            Repeater {
                model: ["خانه", "چارت", "بک‌تست", "ژورنال", "تنظیمات"]
                delegate: TabButton {
                    text: modelData
                    checked: stack.currentIndex === index
                    onClicked: stack.currentIndex = index
                    contentItem: Label { text: parent.text; color: parent.checked ? Theme.accent : Theme.text; horizontalAlignment: Text.AlignHCenter }
                }
            }
            Item { Layout.fillWidth: true }
            BusyIndicator { running: App.busy; visible: running; Layout.preferredHeight: 28; Layout.preferredWidth: 28 }
            Label { text: App.status; color: Theme.muted; elide: Label.ElideLeft; Layout.maximumWidth: 440; Layout.rightMargin: 18 }
        }
    }

    StackLayout {
        id: stack
        anchors.fill: parent
        HomePage {}
        ChartPage {}
        BacktestPage {}
        JournalPage {}
        SettingsPage {}
    }
}
