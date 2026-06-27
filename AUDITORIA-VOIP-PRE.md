# Auditoria Pre-VoIP - procol-chat-mobile

Rama: `custom` | Fecha: 2026-06-26

---

## Resumen ejecutivo

El app tenia tres problemas estructurales criticos: (1) ActionCable se desconectaba sin reconectarse nunca, (2) el selector de notificaciones mutaba el estado de Redux en cada dispatch invalidando el cache completo, y (3) el handler de mensajes FCM crasheaba con payloads malformados. Los tres estan corregidos. La capa de conectividad en tiempo real ahora tiene backoff exponencial, integracion con NetInfo y reconexion al volver de background. Redux solo recalcula lo que cambia. Los tests (182/182) pasan sin regresiones. La base esta en condiciones para iniciar el modulo VoIP, con un prerequisito critico pendiente de aprobacion: implementar notificaciones locales Android en background (actualmente los mensajes data-only de Chatwoot llegan al device pero no se muestran al usuario).

---

## Tabla de hallazgos

| Area | Problema | Severidad | Corregido | Archivo(s) |
|------|----------|-----------|-----------|------------|
| Conectividad | `handleDisconnected` solo hacia `console.log`, sin reconexion | Alta | Si | `baseActionCableConnector.ts:65-67` |
| Conectividad | `setInterval` de `update_presence` sin limpiar - memory leak | Alta | Si | `baseActionCableConnector.ts:43-45` |
| Conectividad | `init()` dejaba instancias huerfanas al reinicializar | Alta | Si | `actionCable.ts:171-174` |
| Conectividad | Vuelta de background solo hacia refetch REST, no verificaba WebSocket | Media | Si | `ConversationScreen.tsx:174-179` |
| Redux/Store | `getFilteredNotifications` mutaba array de entity adapter | Alta | Si | `notificationSelectors.ts:57` |
| Redux/Store | `selectInboxById` sin memoizar, scan lineal por cada fila visible | Media | Si | `inboxSelectors.ts:9-10` |
| UI/Listas | `ListFooterComponent` inline en `InboxScreen` causaba remount en cada dispatch | Media | Si | `InboxScreen.tsx:59-70` |
| UI/Listas | `keyExtractor` inline en `MessagesList` | Baja | Si | `MessagesList.tsx:129-134` |
| Startup | 26 slices de Redux rehydratados en cada arranque | Alta | Si | `store/index.ts` (sesion anterior) |
| Redux/Store | `[...conversations].sort()` mutaba estado en conversationSelectors | Alta | Si | `conversationSelectors.ts:84` (sesion anterior) |
| Push/FCM | `findNotificationFromFCM` crasheaba con payload null/malformado | Alta | Si | `pushUtils.ts:66` |
| Push/FCM | `findConversationLinkFromPush` no aceptaba `null` como argumento | Media | Si | `pushUtils.ts:29` |
| Build | Requisito de Java 17 no documentado en ningun archivo versionado | Media | Si | `README.md` |
| Lint | 58 errores de formato pre-existentes en chat-screen/search/copilot | Baja | No* | varios en `src/screens/chat-screen/` |
| Dependencias | `protobufjs` critico via cadena de Firebase (CVE) | Alta | No** | transitivo via `@react-native-firebase` |
| Dependencias | `lodash` con code injection en `_.template` | Alta | No** | `package.json` |
| Firebase/Android | API modular de `@react-native-firebase/messaging` no migrada | Alta | Si | `navigation/index.tsx`, `settingsActions.ts`, `app.tsx` (sesion anterior) |
| VoIP/Push | Background handler Android no muestra notificacion visible (data-only) | Alta | No*** | `navigation/index.tsx:37-39` |
| Redux/Store | `handleRender` inline en `MessagesList` | Baja | No | `MessagesList.tsx:70` |
| Build | `react-native-gesture-handler` y `reanimated` fuera del rango exacto del SDK | Media | No** | `package.json` |

`*` No corregidos: son pre-existentes y corregirlos en este PR generaria diff ruido innecesario.
`**` No corregidos: requieren prueba de regresion y/o decision informada sobre upgrade.
`***` No implementado: requiere decision de producto (ver seccion Pendientes).

---

## Commits aplicados

Todos en rama `custom`, en orden cronologico:

| Hash | Descripcion |
|------|-------------|
| `6436e03` | perf(startup): reducir AsyncStorage al arrancar y corregir mutacion en selector |
| `770229a` | fix(store): corregir mutacion en-lugar en getFilteredNotifications |
| `457ac2b` | fix(firebase): migrar a API modular de @react-native-firebase/messaging |
| `567badc` | fix(ui): estabilizar ListFooterComponent en FlashList de conversaciones |
| `1b1218a` | perf(store): memoizar selectInboxById con createSelector |
| `6d68e4e` | fix(realtime): reconexion con backoff exponencial en ActionCable |
| `0407c84` | fix(realtime): reconectar ActionCable al volver la app a foreground |
| `f645b99` | fix(ui): estabilizar footer y renderItem en FlashList de InboxScreen |
| `5a2a6a2` | fix(lint): corregir variables sin usar en catch y formato prettier |
| `af2eb34` | fix(inbox): eliminar ref previousSortOrder sin uso |
| `ad0b5a6` | perf(chat): extraer keyExtractor de FlashList de mensajes como constante estable |
| `9f26785` | fix(lint): eliminar variables de catch no usadas (ronda 2) |
| `50641c8` | docs: agregar seccion de setup con requisito de Java 17 para builds Android |

`pushUtils.ts` (guardas de null en FCM handlers) incluido en el commit `6436e03` del agente build-quality que trabajaba en paralelo con el agente fcm-push.

---

## Estado de tests y lint

- **Tests**: 40 suites, 182 tests - todos pasan. Sin regresiones.
- **Lint**: 0 errores en todos los archivos modificados por esta sesion. 58 errores pre-existentes en archivos no tocados.

---

## Listo para VoIP

### Lo que ya es confiable

- **Reconexion en tiempo real**: ActionCable ahora reconecta con backoff exponencial (1s - 2s - 4s - ... - 60s, max 10 intentos), detecta vuelta de red via NetInfo, y se reconecta al volver de background a foreground.
- **Redux sin mutaciones**: Los tres selectores que mutaban estado (conversations, notifications, inbox) estan corregidos. El selector solo se recalcula cuando cambian los datos relevantes.
- **Push/FCM**: El payload de registro de device es correcto. Los handlers de notificaciones no crashean ante payloads inesperados. El FCM token no se expone en logs.
- **Arranque rapido**: Solo 2 slices (auth + settings) se leen de AsyncStorage al iniciar. El resto se fetchea del API.

### Lo que falta para que VoIP funcione en Android

El prerequisito mas critico es el **background handler Android**. Chatwoot envia mensajes FCM de tipo `data-only`. En Android, cuando la app esta en background, Firebase no muestra ninguna notificacion visible si el mensaje no incluye un campo `notification`. El usuario no ve la llamada entrante aunque el device la reciba. Esto bloquea VoIP en Android.

Para resolverlo antes de iniciar el modulo VoIP: implementar `notifee.displayNotification` dentro del `setBackgroundMessageHandler` existente en `navigation/index.tsx`. El paquete `@notifee/react-native@^9.1.1` ya esta instalado y la API necesaria existe en esa version. Requiere tu aprobacion antes de implementarlo (ver Pendientes).

---

## Pendientes y decisiones de producto

### P1 - BLOQUEANTE para VoIP: notificaciones locales Android en background (Alta)

**Problema**: `setBackgroundMessageHandler` en `navigation/index.tsx:37-39` solo hace `console.log`. Los mensajes data-only de Chatwoot no muestran notificacion en Android cuando la app esta en background.

**Propuesta**: Usar `@notifee/react-native` (ya instalado) para mostrar la notificacion manualmente. Requiere definir:
- Canal Android con `AndroidImportance.HIGH`
- Icono y sonido de notificacion
- Comportamiento al presionar (ya existe logica de navegacion en `onNotificationOpenedApp`)

**Impacto**: Sin esto, las llamadas VoIP entrantes son invisibles en Android background. Bajo riesgo tecnico, requiere decision sobre canal, sonido y comportamiento.

---

### P2 - Estrategia de arquitectura VoIP (Alta - decision de producto)

Antes de construir el modulo, decidir entre:

| Opcion | Descripcion | Complejidad |
|--------|-------------|-------------|
| A - Pantalla propia | Full-screen intent con UI propia, sin ConnectionService | Media (3-5 dias) |
| B - ConnectionService | Integracion con el dialer nativo del telefono (como WhatsApp) | Alta (1-2 semanas + modulo nativo) |
| C - SDK de terceros | Twilio, Agora, Daily.co como capa de transporte | Muy alta (dependencia externa) |

La opcion A es la mas rapida y controlable. La opcion B da la mejor experiencia nativa pero requiere `react-native-callkeep` o implementacion propia de `ConnectionService`.

---

### P3 - Permisos de AndroidManifest para VoIP (Media - requiere compliance)

Permisos necesarios que no estan declarados actualmente:

| Permiso | Para que |
|---------|----------|
| `USE_FULL_SCREEN_INTENT` | Mostrar pantalla de llamada sobre lock screen |
| `FOREGROUND_SERVICE` | Mantener servicio de llamada activo |
| `FOREGROUND_SERVICE_PHONE_CALL` | Tipo de foreground service (Android 14+) |
| `RECORD_AUDIO` | Microfono para WebRTC (requiere permiso en runtime) |
| `RECEIVE_BOOT_COMPLETED` | Reiniciar servicio tras reboot |
| `MANAGE_OWN_CALLS` | Si se usa ConnectionService |

Nota: `USE_FULL_SCREEN_INTENT` en Android 14+ requiere que el usuario otorgue el permiso en tiempo de ejecucion via `Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENTS`. Google Play puede rechazar apps que lo usen sin categoria de llamada declarada.

---

### P4 - Degradacion a polling cuando ActionCable falla (Media - UX)

Despues de 10 reintentos fallidos, el cable se da por vencido silenciosamente. El usuario puede hacer pull-to-refresh pero no recibe aviso.

**Opciones**: (a) polling silencioso de 30s + reconexion automatica al detectar red, (b) banner "sin conexion en tiempo real" con boton de reintento, (c) dejar como esta (pull-to-refresh).

Recomendacion: (a) sin banner, ya que los banners de conectividad son molestos en mobile. Bajo riesgo tecnico.

---

### P5 - Vulnerabilidades de dependencias (Alta, no urgente para VoIP)

- `protobufjs` critico via `@react-native-firebase`: actualizar cuando se libere una version de Firebase que incluya `protobufjs >= 7.5.5`. No urgente si FCM funciona correctamente.
- `lodash` code injection en `_.template`: auditar todos los usos en el proyecto para confirmar que el input no viene de usuarios no confiables. Si solo se usan funciones seguras de lodash, el riesgo es bajo.
- 88 vulnerabilidades totales en devDependencies: la mayoria afectan solo el entorno de build, no el bundle de produccion.

---

### P6 - 58 errores de lint pre-existentes (Baja)

En `src/screens/chat-screen/`, `src/screens/search/`, `src/store/conversation/copilot*`. Son mayoria errores de formato prettier y algunos `@typescript-eslint/no-explicit-any`. No introducidos por esta sesion. Limpiar en PR dedicado antes del merge a develop.

---

### P7 - `@notifee/react-native` marcado como "unmaintained" (Alta para VoIP)

El ecosistema React Native Directory lo marca como unmaintained. En la practica la libreria sigue recibiendo PRs y soporta Android 14+. Evaluar si es aceptable como dependencia para VoIP antes de construir sobre ella. Alternativa: `react-native-notifications` (Wix) o manejo nativo puro.

---

## APK de release

No se genero un APK en esta auditoria. Para producirlo:

```bash
# Requiere Java 17 (Java 25 rompe el build - ver README.md)
cd android && ./gradlew assembleRelease
# Salida: android/app/build/outputs/apk/release/app-release.apk
```

El build de release local fue verificado en la sesion anterior (2026-06-25) en un Redmi con Android 15. Los cambios de esta sesion son todos en JS/TS - no hay cambios nativos que requieran rebuildear la capa nativa. Un `eas build --profile production --local` producira el AAB para Play Store.

---

*Generado por auditoria multi-agente en paralelo (4 agentes: conectividad, rendimiento, build/calidad, FCM/push).*
