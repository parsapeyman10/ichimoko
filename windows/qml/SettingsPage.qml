import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import AurumEdge

Page {
    background: Rectangle { color: Theme.background }
    contentItem: ColumnLayout {
        anchors.fill: parent
        anchors.margins: 24
        spacing: 16
        Label { text: "تنظیمات و قرارداد نسخه"; color: Theme.text; font.pixelSize: 24; font.bold: true }
        Frame {
            Layout.fillWidth: true
            background: Rectangle { color: Theme.panel; border.color: Theme.border; radius: 8 }
            contentItem: ColumnLayout {
                spacing: 12
                Label { text: "ریسک بک‌تست: ۰٫۲۵٪ سرمایه در هر معامله"; color: Theme.text }
                Label { text: "کارمزد: ۰٫۱۰٪ هر سمت · Slippage: ۰٫۰۱٪ هر سمت · هدف آزمایشی: 2R"; color: Theme.text; wrapMode: Text.WordWrap }
                Label { text: "در برخورد هم‌زمان SL/TP داخل یک کندل، SL برنده فرض می‌شود."; color: Theme.muted; wrapMode: Text.WordWrap }
                Label { text: "داده‌ها و ژورنال روی دستگاه می‌مانند. این نسخه هیچ معاملهٔ واقعی و API Key ندارد."; color: Theme.green; wrapMode: Text.WordWrap }
            }
        }
        Frame {
            Layout.fillWidth: true
            background: Rectangle { color: Theme.panel; border.color: Theme.border; radius: 8 }
            contentItem: ColumnLayout {
                spacing: 8
                Label { text: "بهینه‌سازی Native"; color: Theme.accent; font.bold: true }
                Label { text: "C++23 بدون Extension در Core · Memory-mapped CSV · Rolling O(n) indicators · Worker Threads · MSVC /O2 /GL + LTCG"; color: Theme.text; wrapMode: Text.WordWrap }
                Label { text: "Native Tune و SIMD فقط با Benchmark و Runtime Dispatch در مراحل بعد فعال می‌شوند تا Binary روی تمام CPUهای x64 سازگار بماند."; color: Theme.muted; wrapMode: Text.WordWrap }
            }
        }
        Frame {
            Layout.fillWidth: true
            background: Rectangle { color: Theme.panel; border.color: Theme.border; radius: 8 }
            contentItem: ColumnLayout {
                spacing: 10
                Label { text: "بروزرسانی ویندوز · نسخهٔ فعلی " + Updates.currentVersion; color: Theme.accent; font.bold: true }
                Label { text: Updates.message; color: Theme.text; wrapMode: Text.WordWrap; Layout.fillWidth: true }
                RowLayout {
                    Button { text: "بررسی نسخهٔ جدید"; enabled: !Updates.loading; onClicked: Updates.check() }
                    Button { text: "باز کردن Release"; visible: Updates.releaseUrl.length > 0; onClicked: Updates.openReleasePage() }
                    BusyIndicator { running: Updates.loading; visible: running; Layout.preferredWidth: 26; Layout.preferredHeight: 26 }
                }
                Label { text: "نصب خودکار فایل ناشناس انجام نمی‌شود. انتشار Installer امضاشده و بررسی Authenticode هنوز لازم است."; color: Theme.muted; wrapMode: Text.WordWrap; Layout.fillWidth: true }
            }
        }
        Item { Layout.fillHeight: true }
        Label { text: "Aurum Edge Windows 0.1.0 — Vertical Slice"; color: Theme.muted }
    }
}
