import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import AurumEdge

Page {
    background: Rectangle { color: Theme.background }
    contentItem: ScrollView {
        anchors.fill: parent
        contentWidth: availableWidth
        ColumnLayout {
            width: parent.width
            anchors.margins: 24
            spacing: 14
            Label { text: "ماشین‌حساب ریسک و ایمنی پلن"; color: Theme.text; font.pixelSize: 24; font.bold: true; Layout.leftMargin: 24 }
            Label { text: "حجم از روی سرمایه، درصد ریسک و فاصلهٔ واقعی SL محاسبه می‌شود؛ اتصال یا سفارش واقعی وجود ندارد."; color: Theme.muted; wrapMode: Text.WordWrap; Layout.fillWidth: true; Layout.leftMargin: 24; Layout.rightMargin: 24 }
            Frame {
                Layout.fillWidth: true; Layout.leftMargin: 24; Layout.rightMargin: 24
                background: Rectangle { color: Theme.panel; border.color: Theme.border; radius: 8 }
                contentItem: GridLayout {
                    columns: 6; rowSpacing: 12; columnSpacing: 10
                    Label { text: "نماد"; color: Theme.muted }
                    Label { text: "موجودی $"; color: Theme.muted }
                    Label { text: "ریسک ٪"; color: Theme.muted }
                    Label { text: "ورود"; color: Theme.muted }
                    Label { text: "حد ضرر"; color: Theme.muted }
                    Label { text: "حد سود"; color: Theme.muted }
                    TextField { id: symbol; text: "XAU/USD"; color: Theme.text; Layout.fillWidth: true }
                    TextField { id: balance; text: "10000"; color: Theme.text; inputMethodHints: Qt.ImhFormattedNumbersOnly; Layout.fillWidth: true }
                    TextField { id: risk; text: "0.5"; color: Theme.text; inputMethodHints: Qt.ImhFormattedNumbersOnly; Layout.fillWidth: true }
                    TextField { id: entry; text: "2000"; color: Theme.text; inputMethodHints: Qt.ImhFormattedNumbersOnly; Layout.fillWidth: true }
                    TextField { id: stop; text: "1990"; color: Theme.text; inputMethodHints: Qt.ImhFormattedNumbersOnly; Layout.fillWidth: true }
                    TextField { id: target; text: "2020"; color: Theme.text; inputMethodHints: Qt.ImhFormattedNumbersOnly; Layout.fillWidth: true }
                    Button {
                        Layout.columnSpan: 3; Layout.fillWidth: true; text: "محاسبهٔ واقعی حجم و هزینه"
                        onClicked: result = App.calculateRisk(symbol.text, Number(balance.text), Number(risk.text), Number(entry.text), Number(stop.text), Number(target.text))
                    }
                    Button {
                        Layout.columnSpan: 3; Layout.fillWidth: true; text: "ایمن‌سازی با TradePlanGuard"
                        onClicked: guarded = App.guardRiskPlan(symbol.text, Number(stop.text) > Number(entry.text) ? "SELL" : "BUY", Number(entry.text), Number(stop.text), Number(target.text), 0, 15)
                    }
                }
            }
            Frame {
                visible: guarded.note !== undefined
                Layout.fillWidth: true; Layout.leftMargin: 24; Layout.rightMargin: 24
                background: Rectangle { color: Theme.panel; border.color: guarded.usable ? Theme.accent : Theme.red; radius: 8 }
                contentItem: ColumnLayout {
                    Label { text: "Peyman TradePlanGuard · " + (guarded.usable ? (guarded.adjusted ? "اصلاح‌شده" : "تأییدشده") : "رد شد"); color: guarded.usable ? Theme.accent : Theme.red; font.bold: true }
                    Label { text: guarded.usable ? "ورود " + Number(guarded.entry).toFixed(5) + " · SL " + Number(guarded.stop).toFixed(5) + " · TP " + Number(guarded.target).toFixed(5) + " · R:R 1:" + Number(guarded.riskReward).toFixed(2) + " · اهرم " + guarded.leverage + "x" : "پلن قابل استفاده نیست"; color: Theme.text; wrapMode: Text.WordWrap; Layout.fillWidth: true }
                    Label { text: guarded.note || ""; color: Theme.muted; wrapMode: Text.WordWrap; Layout.fillWidth: true }
                }
            }
            Frame {
                Layout.fillWidth: true; Layout.leftMargin: 24; Layout.rightMargin: 24
                background: Rectangle { color: Theme.panel; border.color: Theme.border; radius: 8 }
                contentItem: GridLayout {
                    columns: 4; rowSpacing: 10; columnSpacing: 18
                    Label { text: "جهت"; color: Theme.muted } Label { text: result.side || "—"; color: result.side === "SELL" ? Theme.red : Theme.green }
                    Label { text: "بودجهٔ ریسک"; color: Theme.muted } Label { text: result.riskBudget === undefined ? "—" : "$" + Number(result.riskBudget).toFixed(2); color: Theme.text }
                    Label { text: "حجم / لات"; color: Theme.muted } Label { text: result.units === undefined ? "—" : Number(result.units).toFixed(4) + " / " + Number(result.lots).toFixed(4); color: Theme.text }
                    Label { text: "R:R"; color: Theme.muted } Label { text: result.riskReward === undefined ? "—" : "1:" + Number(result.riskReward).toFixed(2); color: Theme.text }
                    Label { text: "اهرم / مارجین"; color: Theme.muted } Label { text: result.leverage === undefined ? "—" : result.leverage + "x / $" + Number(result.margin).toFixed(2); color: Theme.text }
                    Label { text: "ارزش اسمی"; color: Theme.muted } Label { text: result.notional === undefined ? "—" : "$" + Number(result.notional).toFixed(2); color: Theme.text }
                    Label { text: "کمیسیون رفت‌وبرگشت"; color: Theme.muted } Label { text: result.commission === undefined ? "—" : "$" + Number(result.commission).toFixed(2); color: Theme.text }
                    Label { text: "هزینهٔ اسپرد"; color: Theme.muted } Label { text: result.spread === undefined ? "—" : "$" + Number(result.spread).toFixed(2); color: Theme.text }
                    Label { text: "سود/ضرر در TP/SL"; color: Theme.muted } Label { text: result.profitAtTp === undefined ? "—" : "+$" + Number(result.profitAtTp).toFixed(2) + " / -$" + Number(result.lossAtSl).toFixed(2); color: Theme.text }
                    Label { text: "لیکوییدیشن تقریبی"; color: Theme.muted } Label { text: result.liquidation === undefined ? "—" : Number(result.liquidation).toFixed(5) + " · " + Number(result.liquidationBufferR).toFixed(2) + "R"; color: Theme.text }
                    Label { text: "فاصلهٔ SL / TP"; color: Theme.muted } Label { text: result.stopPct === undefined ? "—" : Number(result.stopPct).toFixed(3) + "% / " + Number(result.targetPct).toFixed(3) + "%"; color: Theme.text }
                    Label { Layout.columnSpan: 4; Layout.fillWidth: true; text: result.note || "مقادیر را وارد و محاسبه کنید."; color: result.valid === false ? Theme.red : Theme.muted; wrapMode: Text.WordWrap }
                }
            }
        }
    }
    property var result: ({})
    property var guarded: ({})
}
