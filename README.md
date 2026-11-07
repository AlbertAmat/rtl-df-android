# RDF · Radiogoniometría en Android (Kotlin)

App Android para localizar emisores con una Yagi: el móvil va montado en la antena y hace de pantalla, GPS y brújula; el RTL-SDR se conecta por USB OTG y lo sirve la app **SDR driver** (`rtl_tcp` en `127.0.0.1:1234`).

**Flujo**
1. **Escanear**: barre 400–470 MHz (rango de la Yagi Ailunce AY02) el tiempo que elijas y lista las emisoras con nivel, SNR y % de actividad. Atajos: Yagi completa, PMR 446, 70 cm, ISM 433.
2. **Rastrear**: eliges una emisora de la lista (o escribes la frecuencia). Potencia en vivo con número, gráfica y **tono de audio** (más agudo = más señal).
3. **Registrar punto** (botón o **tecla de volumen**): guarda posición GPS, hora (del GPS), frecuencia, potencia, SNR, ganancia y rumbo. El rumbo sale de la brújula del móvil, con la declinación magnética calculada sola a partir del GPS.
4. Con 2 o más puntos calcula la posición del emisor (intersección / mínimos cuadrados) y la dibuja en el mapa con los rayos.

## Compilar e instalar
1. Android Studio reciente (Ladybug o superior). **File → Open** y elige esta carpeta; espera al *Sync* (descarga dependencias, necesita internet una vez).
2. Si Studio propone actualizar AGP/Kotlin/Compose, puedes aceptar; las versiones fijadas son un conjunto compatible conocido (AGP 8.7.3, Gradle 8.9, Kotlin 2.0.21).
3. Conecta el móvil con depuración USB y pulsa **Run**, o **Build → Build APK(s)**.

## Primer uso
1. Abre **SDR driver**, conecta el RTL-SDR (acepta el permiso USB) y pulsa **Start** (dirección `127.0.0.1`, puerto `1234`).
2. Abre RDF y concede el permiso de ubicación. En **Rastrear** pulsa **Conectar SDR** (también hay un botón para abrir SDR driver).
3. Ve a **Escanear** → **Iniciar escaneo**.

Consejos: activa el **modo avión** en campo (el GPS sigue funcionando y la radio del móvil no interfiere con el RTL-SDR), y deja la pantalla encendida: la app la mantiene activa porque la captura se para si se apaga.

## Montaje del móvil en la Yagi
- Pon el móvil **plano** sobre el boom con el borde superior apuntando hacia delante (es la opción por defecto). Si lo montas de otra forma, elígelo en **Ajustes → Montaje**.
- Soporte 3D en **PETG/PLA, sin metal ni imanes** (tornillos de acero, soportes magnéticos…): perturban la brújula. Aléjalo del dongle y del boom todo lo que puedas.
- Calibra la brújula moviendo el móvil en forma de 8 con el montaje puesto (la pestaña Rastrear muestra la calibración).
- **Corrección de rumbo**: apunta la Yagi a algo con rumbo conocido y pon la diferencia en Ajustes (compensa que el móvil no quede perfectamente alineado).
- Las **teclas de volumen** registran el punto sin tocar la pantalla; la app promedia el rumbo de los 2,5 s anteriores a la pulsación (pulsar mueve la antena).

## Mapa offline
Encuadra la zona de trabajo en el mapa y, en **Ajustes → Mapa offline**, pulsa **Descargar la zona visible** (zoom 12–16 por defecto, tope de 2000 teselas por la política de uso de OpenStreetMap). Luego activa **Mapa solo offline**. Las teselas se guardan en el móvil.

## Ajustes útiles
- **Asentamiento (ms)**: tiempo que se descarta tras cambiar de frecuencia, necesario porque `rtl_tcp` emite sin parar. Con 80 ms un barrido de 70 MHz tarda unos 8 s. Si aparecen emisoras fantasma del salto anterior, súbelo; si quieres ir más rápido, bájalo.
- **ppm**: corrección de frecuencia del RTL-SDR (los baratos se desvían decenas de kHz).
- Ganancia vacía o `auto` = AGC del tuner. En rastreo empieza con ~30 dB y bájala si sale «Señal saturada». Las potencias son dBFS relativos (sin calibrar): sirven para comparar entre puntos con la misma ganancia.

## Estado de verificación
- La lógica (cliente `rtl_tcp`, DSP, escáner, rastreador, trigonometría) se compiló con Kotlin 2.0.21 y pasa 11 pruebas JVM contra un servidor `rtl_tcp` falso (`app/src/test`): detecta portadoras a ±3 kHz, mide SNR y localiza el emisor con error de metros en datos sintéticos.
- **La parte Android (interfaz Compose, sensores, GPS, osmdroid, base de datos) no se ha podido compilar ni probar en el entorno donde se generó** (sin Android SDK ni acceso a Maven). Si Gradle da algún error de compilación o la app falla en el móvil, pásame el mensaje y lo corrijo.
- No está probada con un RTL-SDR real: el tiempo de asentamiento y el comportamiento de SDR driver pueden requerir ajustes.

## Estructura
| Archivo | Qué hace |
|---|---|
| `RtlTcp.kt`, `Sdr.kt` | protocolo rtl_tcp, configuración, sintonía y captura sin datos viejos |
| `Dsp.kt` | FFT, espectro promediado, mediana, percentiles |
| `Scanner.kt` | barrido por saltos, máximos, % de actividad, agrupación en emisoras |
| `Tracker.kt` | potencia y SNR en vivo; potencia del punto = percentil 90 de 3 s |
| `Geo.kt` | intersección / mínimos cuadrados de rumbos |
| `Sensors.kt` | GPS (LocationManager), brújula (vector de rotación), declinación |
| `Db.kt`, `Export.kt` | SQLite, CSV y GeoJSON compartibles |
| `MainViewModel.kt`, `Ui.kt`, `MapOverlays.kt`, `MainActivity.kt` | estado, interfaz Compose, mapa, teclas de volumen |

Pruebas JVM: `./gradlew test` (o *Run tests* en Android Studio).
