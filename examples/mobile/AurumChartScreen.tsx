// Expo / React Native example. Install react-native-webview and bundle chart.html as an app asset.
import { useEffect, useRef } from 'react';
import { SafeAreaView, StyleSheet, Text, View } from 'react-native';
import { WebView } from 'react-native-webview';

type Candle = { time: number; open: number; high: number; low: number; close: number; volume: number };
type Props = { candles: Candle[]; latest?: Candle; timeframe: '1m' | '5m' };

export function AurumChartScreen({ candles, latest, timeframe }: Props) {
  const webview = useRef<WebView>(null);
  const send = (payload: object) => webview.current?.postMessage(JSON.stringify(payload));

  useEffect(() => send({ type: 'bootstrap', candles, timeframe }), [candles, timeframe]);
  useEffect(() => { if (latest) send({ type: 'candle', candle: latest }); }, [latest]);

  return (
    <SafeAreaView style={styles.screen}>
      <View style={styles.header}>
        <View><Text style={styles.symbol}>XAU / USD</Text><Text style={styles.caption}>GOLD SPOT · PAPER MODE</Text></View>
        <Text style={styles.timeframe}>{timeframe}</Text>
      </View>
      <WebView
        ref={webview}
        source={require('./chart.html')}
        originWhitelist={['*']}
        javaScriptEnabled
        scrollEnabled={false}
        onLoadEnd={() => send({ type: 'bootstrap', candles, timeframe })}
        onMessage={(event) => {
          const message = JSON.parse(event.nativeEvent.data);
          if (message.type === 'crosshair') console.log('Selected candle', message.candle);
        }}
        style={styles.chart}
      />
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  screen: { flex: 1, backgroundColor: '#07090d' },
  header: { height: 58, paddingHorizontal: 16, flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', borderBottomColor: '#212630', borderBottomWidth: 1 },
  symbol: { color: '#e8eaf0', fontSize: 14, fontWeight: '700' },
  caption: { color: '#7d8492', fontSize: 9, marginTop: 3 },
  timeframe: { color: '#f1bc4b', backgroundColor: '#2b2619', paddingHorizontal: 10, paddingVertical: 6, borderRadius: 5 },
  chart: { flex: 1, backgroundColor: '#0b0e13' },
});
