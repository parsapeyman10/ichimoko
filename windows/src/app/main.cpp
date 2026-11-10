#include "app/AppController.h"
#include "app/CandleChart.h"

#include <QGuiApplication>
#include <QIcon>
#include <QQmlApplicationEngine>
#include <QQmlContext>
#include <QQuickStyle>
#include <QTimer>

int main(int argc, char* argv[]) {
    QGuiApplication app(argc, argv);
    QGuiApplication::setOrganizationName(QStringLiteral("Aurum"));
    QGuiApplication::setApplicationName(QStringLiteral("Aurum Edge"));
    QQuickStyle::setStyle(QStringLiteral("Material"));
    qmlRegisterType<aurum::CandleChart>("AurumEdge", 1, 0, "CandleChart");
    aurum::AppController controller;
    QQmlApplicationEngine engine;
    engine.rootContext()->setContextProperty(QStringLiteral("App"), &controller);
    QObject::connect(&engine, &QQmlApplicationEngine::objectCreationFailed,
        &app, [] { QCoreApplication::exit(1); }, Qt::QueuedConnection);
    engine.loadFromModule("AurumEdge", "Main");
    if (app.arguments().contains(QStringLiteral("--smoke-test"))) {
        QTimer::singleShot(8000, &app, &QCoreApplication::quit);
    }
    return app.exec();
}
