import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import AurumEdge

Page {
    background: Rectangle { color: Theme.background }
    contentItem: ColumnLayout {
        anchors.fill: parent; anchors.margins: 28; spacing: 18
        Label { text: "دادهٔ زندهٔ بازار"; color: Theme.text; font.pixelSize: 25; font.bold: true }
        Label { text: "فید عمومی و بدون کلید؛ هر ۱۰ ثانیه فقط هنگام فعال‌بودن. هیچ اتصال حساب یا ارسال سفارشی وجود ندارد."; color: Theme.muted; wrapMode: Text.WordWrap; Layout.fillWidth: true }
        Frame {
            Layout.fillWidth: true
            background: Rectangle { color: Theme.panel; border.color: Theme.border; radius: 10 }
            contentItem: RowLayout {
                spacing: 12
                ComboBox { id: symbol; model: ["BTC/USDT", "ETH/USDT", "SOL/USDT", "XAU/USD", "XAG/USD", "EUR/USD", "USD/JPY"]; enabled: !Live.running }
                Button { text: Live.running ? "توقف فید" : "شروع فید"; onClicked: Live.running ? Live.stop() : Live.start(symbol.currentText) }
                Button { text: "دریافت فوری"; enabled: Live.running && !Live.loading; onClicked: Live.refresh() }
                BusyIndicator { running: Live.loading; visible: running; Layout.preferredWidth: 30; Layout.preferredHeight: 30 }
            }
        }
        Frame {
            Layout.fillWidth: true
            background: Rectangle { color: Theme.panel; border.color: Theme.border; radius: 10 }
            contentItem: ColumnLayout {
                spacing: 12
                Label { text: Live.symbol; color: Theme.muted; font.pixelSize: 18 }
                Label { text: Live.price > 0 ? Number(Live.price).toLocaleString(Qt.locale(), 'f', 6) : "—"; color: Theme.accent; font.pixelSize: 42; font.bold: true }
                Label { text: Live.provider || "Provider: —"; color: Theme.text }
                Label { text: Live.receivedAtMs > 0 ? new Date(Live.receivedAtMs).toLocaleString() : "هنوز Quote دریافت نشده"; color: Theme.muted }
                Label { text: Live.message; color: Live.price > 0 ? Theme.green : Theme.muted; wrapMode: Text.WordWrap; Layout.fillWidth: true }
            }
        }
        Label { text: "Quote لحظه‌ای الزاماً قیمت قابل اجرا نیست؛ اسپرد، تأخیر و اختلاف Provider باید در تصمیم Paper لحاظ شود."; color: Theme.muted; wrapMode: Text.WordWrap; Layout.fillWidth: true }
        Item { Layout.fillHeight: true }
    }
    Component.onDestruction: Live.stop()
}
