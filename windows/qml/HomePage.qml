import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import AurumEdge

Page {
    background: Rectangle { color: Theme.background }
    contentItem: ColumnLayout {
        anchors.fill: parent
        anchors.margins: 28
        spacing: 20
        Label { text: "مرکز فرماندهی Aurum Edge"; color: Theme.text; font.pixelSize: 28; font.bold: true }
        Label { text: "Native C++23 + Qt6 · داده روی دستگاه · بدون معاملهٔ واقعی"; color: Theme.muted; font.pixelSize: 15 }
        GridLayout {
            columns: Math.max(1, Math.floor(width / 280))
            Layout.fillWidth: true
            rowSpacing: 16
            columnSpacing: 16
            Repeater {
                model: [
                    { title: "کندل معتبر", value: App.candleCount.toLocaleString(), note: "CSV واقعی، بدون دادهٔ ساختگی" },
                    { title: "معاملهٔ ژورنال", value: App.journalTrades.length.toString(), note: "ذخیره‌شده در SQLite محلی" },
                    { title: "وضعیت اجرا", value: App.busy ? "در حال محاسبه" : "آماده", note: "Worker Thread؛ رابط آزاد است" },
                    { title: "پلتفرم", value: "x64 / C++23", note: "Release LTO · مسیر Native" }
                ]
                delegate: Frame {
                    Layout.fillWidth: true
                    Layout.preferredHeight: 145
                    background: Rectangle { color: Theme.panel; radius: 12; border.color: Theme.border }
                    contentItem: ColumnLayout {
                        Label { text: modelData.title; color: Theme.muted }
                        Label { text: modelData.value; color: Theme.text; font.pixelSize: 25; font.bold: true }
                        Label { text: modelData.note; color: Theme.muted; wrapMode: Text.WordWrap }
                    }
                }
            }
        }
        Frame {
            Layout.fillWidth: true
            Layout.fillHeight: true
            background: Rectangle { color: Theme.panel; radius: 12; border.color: Theme.border }
            contentItem: ColumnLayout {
                anchors.fill: parent
                anchors.margins: 20
                spacing: 12
                Label { text: "برش اول قابل اجرا"; color: Theme.accent; font.pixelSize: 20; font.bold: true }
                Label { color: Theme.text; wrapMode: Text.WordWrap; Layout.fillWidth: true; text: "خواندن Dukascopy CSV با Memory Map، تشخیص و حذف ردیف خراب، چارت Native، بک‌تست ایچیموکو مستقل با کارمزد و Slippage، و ژورنال واقعی SQLite." }
                Label { color: Theme.muted; wrapMode: Text.WordWrap; Layout.fillWidth: true; text: "موتور Peyman، نئوویو، یادگیری عصبی، دادهٔ زنده، آرشیو و Update هنوز در این برش ادعای تکمیل ندارند و طبق Feature Matrix پورت می‌شوند." }
                Item { Layout.fillHeight: true }
                Label { text: App.fileName.length ? App.fileName : "فایلی انتخاب نشده است"; color: Theme.text }
                Label { text: App.resultSummary.length ? App.resultSummary : "نتیجهٔ بک‌تست: —"; color: Theme.green; wrapMode: Text.WordWrap }
            }
        }
    }
}
