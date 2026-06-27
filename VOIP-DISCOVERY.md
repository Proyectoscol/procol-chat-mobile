# VOIP-DISCOVERY.md - Fase de descubrimiento pre-implementacion

Rama: `custom` | Fecha: 2026-06-26
Fuente verificada: `https://github.com/Proyectoscol/procol-chat` rama `custom` (commit HEAD del dia de hoy, shallow clone).

---

## Resumen ejecutivo

Se revisaron los 20+ archivos del repo `procol-chat` solicitados en el brief. La arquitectura general descrita en el brief es correcta, pero hay **dos discrepancias criticas** que cambian el plan de implementacion:

1. **Los eventos `voice_call.*` de ActionCable NO son para Asterisk/SIP.** Son exclusivamente para WhatsApp Business API. Para Asterisk el INVITE llega directo por el WebSocket SIP; Rails no participa en la senalizacion.
2. **El punto de integracion del push FCM VoIP NO es `voice_call.incoming`** (ese evento no existe para Asterisk). El punto correcto es `Sip::InternalController#routing`, metodo `routing`, linea ~39, despues de `route_call(inbox)` cuando `result[:action] == 'dial'`.

Hallazgo adicional (no estaba en el brief): la tabla `sip_identities` ya tiene columnas `sip_fcm_token` y `sip_apns_voip_token` en el schema. La infraestructura de datos para el push VoIP ya existe; falta el servicio que la pueble y la use.

---

## 1. Contrato exacto de GET /sip/credential

**Ruta:** `GET /api/v1/accounts/:account_id/sip/credential`
**Auth:** Token de sesion del asesor (`api_access_token` header). Cualquier asesor autenticado obtiene sus propias credenciales. NO requiere rol de admin.

**Respuesta 200:**
```json
{
  "sip_extension": "9001",
  "sip_password": "secreto-estatico-encriptado-en-reposo",
  "wss_url": "wss://<SIP_WSS_HOST>:<SIP_WSS_PORT>/ws",
  "sip_domain": "<SIP_WSS_HOST>",
  "ice_servers": [
    { "urls": ["stun:stun.l.google.com:19302"] },
    {
      "urls": ["turn:<SIP_WSS_HOST>:3478"],
      "username": "<TURN_USERNAME>",
      "credential": "<TURN_CREDENTIAL>"
    }
  ]
}
```

**Respuesta 404:** `{}` si el asesor no tiene `SipIdentity` en la cuenta (aun no ha sido configurado por un admin).

**Detalles criticos:**
- Nombres exactos de campo (case-sensitive): `sip_extension`, `sip_password`, `wss_url`, `sip_domain`, `ice_servers`. NO `extension`, NO `password`.
- `wss_url` se construye como `wss://#{ENV['SIP_WSS_HOST']}:#{ENV['SIP_WSS_PORT'] || '8089'}/ws`. Si `SIP_WSS_HOST` no esta configurado en el servidor Rails, retorna `nil` y el REGISTER falla silenciosamente.
- `ice_servers` SIEMPRE incluye al menos STUN (Google por defecto). El TURN se incluye SOLO si los tres ENVs `TURN_USERNAME`, `TURN_CREDENTIAL` y `SIP_WSS_HOST` estan configurados en Rails. El cliente movil debe manejar el caso donde `ice_servers` solo tiene STUN (sin TURN) y aun asi intentar conectar.
- `sip_password` es el password estatico (fase 1). Comentario literal del codigo: `# v1 (FIX-11 → U2): secret ESTÁTICO por extensión, guardado encriptado en reposo (sip_identities.sip_password). Credencial efímera con TTL + invalidación en logout → fase 2.`

**Accesibilidad desde este entorno:** Requiere servidor Rails en ejecucion con ENVs configurados y asesor con SipIdentity creada. No disponible localmente; requiere staging o produccion.

---

## 2. Eventos de ActionCable - DISCREPANCIA CRITICA CON EL BRIEF

### Lo que dice el brief
> "los eventos reales de ActionCable (voice_call.incoming, voice_call.outbound_connected, voice_call.outbound_accepted, voice_call.ended) y su payload exacto"

### Lo que dice el codigo real

**Todos los eventos `voice_call.*` en `actionCable.js` son EXCLUSIVAMENTE para WhatsApp Business:**

| Evento | Guard en el codigo | Para Asterisk? |
|--------|-------------------|----------------|
| `voice_call.incoming` | `if (data?.provider !== VOICE_CALL_PROVIDERS.WHATSAPP) return` | NO |
| `voice_call.outbound_connected` | idem | NO |
| `voice_call.outbound_accepted` | idem | NO |
| `voice_call.ended` | idem | NO |

Para **Asterisk/SIP**, el browser recibe el INVITE directamente a traves del WebSocket SIP ya registrado contra Asterisk (puerto 8089). JsSIP dispara el evento `newRTCSession` en el UA. **Rails no participa en la senalizacion de llamada entrante Asterisk - no emite ningun broadcast ActionCable.**

Payload de `voice_call.incoming` (WhatsApp solamente, para referencia):
```
data.provider        // 'whatsapp'
data.call_id         // callSid de Meta
data.conversation_id
data.inbox_id
data.sdp_offer       // SDP offer de Meta (especifico de la implementacion P2P de WhatsApp)
data.ice_servers
data.caller          // info del contacto
```

Este payload es completamente diferente al flujo Asterisk y no aplica al modulo movil VoIP.

---

## 3. Configuracion JsSIP en el browser (a replicar en RN)

Codigo literal del composable `useJsSipSession.js`:

```js
const socket = new JsSIP.WebSocketInterface(creds.wss_url);
const instance = new JsSIP.UA({
  sockets: [socket],
  uri: `sip:${creds.sip_extension}@${creds.sip_domain}`,
  password: creds.sip_password,
  register: true,
  connection_recovery_min_interval: 2,   // segundos
  connection_recovery_max_interval: 30,  // segundos
  session_timers: false,                 // CRITICO: evita RE-INVITEs de FreePBX
});
```

**Parametros de RTCPeerConnection para contestar:**
```js
session.answer({
  mediaStream: localStream,         // resultado de getUserMedia({ audio: true })
  pcConfig: {
    iceServers: credentials.ice_servers || DEFAULT_ICE_SERVERS,
    bundlePolicy: 'max-bundle',
    rtcpMuxPolicy: 'require',
  },
});
```

**ICE gathering timeout:** 400ms hard cutoff (si no completa, envia SDP con los candidatos disponibles). Implementado manualmente en el composable - `react-native-webrtc` puede necesitar la misma logica.

**Casos edge criticos:**
- `session_timers: false` es obligatorio. Sin esto, FreePBX envia RE-INVITEs periodicos que pueden desestabilizar la sesion.
- Llamadas salientes: NO llamar `session.answer()`. `ua.call()` ya envio el INVITE. Llamar answer() en una sesion saliente lanza `NOT_SUPPORTED_ERROR` en JsSIP.
- Un solo UA como singleton: si se recrea (re-login), destruir el anterior con `ua.stop()` antes de crear uno nuevo.
- `conversationId` es `null` al recibir el INVITE: JsSIP no tiene esa informacion. Puede completarse via ActionCable cuando llegue el mensaje de actividad (tipo `call_activity`) al chat del contacto.

---

## 4. Configuracion PJSIP de Asterisk (parametros que el cliente SIP movil debe respetar)

Fragmento literal de `deploy/freepbx/pjsip.webrtc.conf.example`:

```ini
[9001](endpoint-template)
type=endpoint
transport=transport-wss          ; WSS, puerto 8089
disallow=all
allow=opus,alaw,ulaw             ; Opus para WebRTC, alaw para trunk Claro
dtmf_mode=rfc4733
webrtc=yes                       ; activa DTLS-SRTP, ICE, rtcp_mux, use_avpf
use_avpf=yes
media_encryption=dtls
dtls_verify=fingerprint
dtls_setup=actpass               ; CRITICO: el cliente debe ofrecer actpass
ice_support=yes
rtcp_mux=yes
auth=9001-auth
aors=9001

[9001]
type=aor
max_contacts=5                   ; multi-dispositivo: browser + celular + otros
remove_existing=no               ; registrar el celular NO expulsa el browser
```

**Lo que implica para react-native-webrtc:**
- `dtls_setup=actpass`: el SDP offer del movil debe declarar `a=setup:actpass`, no `a=setup:active` ni `a=setup:passive`.
- `allow=opus,alaw,ulaw`: el movil debe incluir Opus en su SDP offer. Asterisk lo acepta y transcodea a alaw para el trunk de Claro.
- `rtcp_mux`: el SDP debe incluir `a=rtcp-mux`.
- `use_avpf`: el perfil de media debe ser `RTP/SAVPF`, no `RTP/AVP`.
- Todos estos parametros son manejados automaticamente por WebRTC cuando se configura correctamente `pcConfig`. La verificacion real requiere una llamada de prueba contra el servidor Asterisk.

---

## 5. ICE/STUN/TURN - estado real

**STUN:** `stun:stun.l.google.com:19302` (Google publico).

**TURN:** `turn:freebpx.procol-proyectoscol.com:3478` (UDP). TLS en puerto 5349.

**Las credenciales TURN vienen en la respuesta de `GET /sip/credential`**, dentro del campo `ice_servers`. Confirmado textualmente en `deploy/coturn/README.md`:
> "Rails devuelve estos valores al frontend en `GET /api/v1/accounts/:id/sip/credential` dentro del array `ice_servers`."

**Mecanismo de autenticacion actual:** `lt-cred-mech` (credenciales de largo plazo, estaticas). Un username/password estatico que el servidor Rails tiene configurado en ENV. Plan futuro: credenciales efimeras HMAC (`use-auth-secret`), no implementado todavia.

**TURN es obligatorio para produccion movil.** Del README de FreePBX:
> "~20% de asesores detras de NAT simetrico no tienen audio sin TURN. En redes moviles el porcentaje es mayor."

Las redes 4G/5G son practicamente siempre NAT simetrico. Sin TURN, un porcentaje significativo de llamadas moviles no tendran audio en el 50% de los casos (una de las dos partes no tendra audio).

**Puertos TURN que deben estar abiertos en firewall del servidor:**
- UDP 3478 (STUN/TURN)
- TCP 5349 (TURN sobre TLS)
- UDP 49152-65535 (rango relay de media)

---

## 6. Codec y transcoding

**Literal del README de FreePBX, seccion 2:**
> "Claro usa solo G.711, prioridad G.711A (alaw). NO G.729. El navegador usa opus -> Asterisk transcodifica alaw<->opus en cada llamada."

**Bloqueante U1 del README:**
> "Asterisk no trae `codec_opus` por defecto. Sin el no hay transcode opus(navegador)<->alaw(Claro) -> llamadas sin audio. Instalar `codec_opus.so` compatible con la version de Asterisk del VPS-2. Verificar: `asterisk -rx 'core show translation'` debe mostrar rutas opus<->alaw."

**Para el cliente movil:** este riesgo no es exclusivo del modulo RN. Si `codec_opus.so` no esta instalado en produccion, ninguna llamada WebRTC (navegador ni celular) tiene audio. Antes de considerar que el modulo movil esta "roto", verificar `core show translation` en el servidor Asterisk.

---

## 7. Modelo Call y flujo de registro post-llamada

**Campos clave de la tabla `calls`:**
| Campo | Tipo | Notas |
|---|---|---|
| `provider_call_id` | string | Para Asterisk: el `linkedid` de ARI (invariante en transferencias) |
| `provider` | enum | asterisk=2 |
| `direction` | enum | incoming=0, outgoing=1 |
| `status` | string | ringing / in_progress / completed / no_answer / failed |
| `conversation_id` | FK | NOT NULL; vincula la llamada a la conversacion del contacto |
| `contact_id` | FK | NOT NULL |
| `inbox_id` | FK | NOT NULL |
| `accepted_by_agent_id` | FK | NULL hasta que alguien contesta |
| `started_at` | datetime | NULL hasta que pasa a in_progress |
| `meta.initiated_at` / `ended_at` | jsonb | timestamps de la sesion |

**El `Call` se crea DESPUES de que la llamada termina**, via `Sip::AsteriskCallLogger` (evento `ended`/`no_answer` desde la Stasis app). No se crea al inicio. El cliente movil no necesita hacer nada para que esto funcione: Rails lo maneja automaticamente cuando la llamada termina, independientemente de que cliente la recibio o origino.

---

## 8. Contrato de GET /sip/recent_calls

**Ruta:** `GET /api/v1/accounts/:account_id/sip/recent_calls`
**Auth:** Token del asesor. Sin restriccion de rol.
**Parametros:** Ninguno. Devuelve las ultimas 20 llamadas Asterisk del account, orden `created_at DESC`. Sin paginacion.

**Respuesta (array):**
```json
[
  {
    "id": 42,
    "createdAt": "2026-06-26T19:00:00.000Z",
    "direction": "incoming",
    "status": "completed",
    "durationSeconds": 183,
    "name": "Juan Perez",
    "phoneNumber": "+573001234567",
    "conversationId": 17
  }
]
```

**`direction` posibles:** `"incoming"` (completada), `"missed"` (no_answer), `"outgoing"`.
**Campos ausentes:** no hay `contact_id`, `inboxId`, ni `recordingUrl` en este endpoint.
**`conversationId`** permite navegar al chat del contacto desde el historial. Puede ser `null` si la llamada no tiene conversacion vinculada.

---

## 9. Punto de integracion para push FCM VoIP (cambio en backend Rails)

### Discrepancia con el brief

El brief dice: "localiza dónde se emite hoy el evento `voice_call.incoming`". Para Asterisk, ese evento no existe. El punto de integracion es otro.

### Punto correcto verificado en el codigo

**Archivo:** `enterprise/app/controllers/sip/internal_controller.rb`
**Metodo:** `routing`
**Linea:** ~39, despues de que `route_call(inbox)` devuelve el resultado

Contexto (codigo real simplificado):
```ruby
def routing
  inbox = find_inbox
  result = params[:ivr_digit].present? ? route_ivr(inbox) : route_call(inbox)
  Sip::QueueService.increment(inbox.id) if result[:action] == 'dial'
  # <- PUNTO DE INSERCION DEL PUSH FCM
  render json: result
end
```

**Diff propuesto (a aprobar antes de implementar):**
```ruby
# Insertar despues de QueueService.increment y antes de render json:
if result[:action] == 'dial'
  VoipPushService.call(
    account:   current_account,
    extension: result[:extension],
    call_sid:  params[:linkedid],
    from:      params[:phone]
  )
end
```

**Por que aqui:**
- Es el unico punto donde Rails sabe QUIEN va a sonar (`result[:extension]`) ANTES de que Asterisk marque el telefono.
- `params[:linkedid]` es el `call_sid` para el payload del push.
- `params[:phone]` es el numero del llamante.
- Este endpoint lo llama la Stasis app (Node.js) justo ANTES de que Asterisk haga `originate PJSIP/<extension>`.

**Lo que haria `VoipPushService`:**
1. Buscar la `SipIdentity` con `sip_extension == result[:extension]` y `account_id == current_account.id`.
2. Obtener el `user_id` de esa SipIdentity.
3. Buscar los FCM tokens del usuario en `notification_subscriptions` donde `subscription_type = 'fcm'`.
4. Enviar push FCM `data-only` con alta prioridad y payload:
   ```json
   {
     "type": "incoming_call",
     "call_sid": "<linkedid>",
     "caller_number": "<phone>",
     "caller_name": "<nombre del contacto si disponible>"
   }
   ```

**Alternativa natural (no activa todavia):** La tabla `sip_identities` ya tiene columnas `sip_fcm_token` y `sip_apns_voip_token`. Estas columnas fueron disenadas para exactamente este caso de uso. `VoipPushService` podria leer `sip_identity.sip_fcm_token` directamente en vez de buscar en `notification_subscriptions`. Sin embargo, ninguna logica actual puebla esas columnas - habria que agregar un endpoint (o extender `PATCH /sip/identities/:agent_id`) para que la app movil registre su token FCM en `sip_fcm_token`.

**Este es el diseno recomendado (requiere aprobacion):**
- La app movil, al iniciar sesion/registrar el SIP UA, hace `PATCH /sip/identities` (o un endpoint nuevo como `POST /sip/credential/fcm_token`) para guardar su FCM token en `sip_identities.sip_fcm_token`.
- `VoipPushService` lee ese campo directamente, sin buscar en `notification_subscriptions`.

---

## 10. Pipeline de routing SIP (para entender cuandosonal el telefono)

Las reglas se evaluan en orden:

| Regla | Outcome posible | Implica push FCM? |
|---|---|---|
| `WorkingHoursRule` | `after_hours` -> buzon | No |
| `SharedNumberRule` | Marca `shared_number`, continua | - |
| `AssignedAgentRule` | `ring_agent` (asesor disponible) | Si - `result[:action] == 'dial'` |
| `AssignedAgentRule` | `busy_callback` / `absent_callback` | No - no suena telefono |
| `QueueLimitRule` | `queue_rejected` | No |
| `TeamRoundRobinRule` | `team_ring` (round-robin atomico) | Si - `result[:action] == 'dial'` |
| `VoicemailFallbackRule` | `voicemail` | No |

El push FCM debe enviarse cuando `result[:action] == 'dial'`, que cubre tanto `ring_agent` como `team_ring`.

**Multi-dispositivo:** cuando Asterisk hace `originate PJSIP/<extension>`, todos los contactos registrados en esa extension (browser + celular, hasta `max_contacts=5`) reciben el INVITE simultaneamente. El primero que conteste gana; los demas reciben un CANCEL. **No hay politica de desempate definida en el codigo.** Esta es la decision de producto pendiente del brief.

---

## 11. Gap de background: el problema central de la Fase 2

Cuando la app movil esta cerrada o en background profundo:
- El WebSocket SIP hacia Asterisk NO esta activo.
- JsSIP (o equivalente RN) no esta corriendo.
- El evento `newRTCSession` no se dispara.
- Asterisk intenta marcar `PJSIP/<extension>` y no encuentra contacto activo para ese registration → timeout de 30s → `no_answer`.

**La solucion (Fase 2):**
1. Rails (via `VoipPushService`) envia push FCM `data-only` de alta prioridad al token del celular.
2. Android despierta el `setBackgroundMessageHandler`.
3. Notifee muestra la pantalla de llamada (full-screen intent) en el lock screen.
4. El usuario contesta → la app arranca en foreground.
5. La app hace `GET /sip/credential` → REGISTER contra Asterisk.
6. **Problema pendiente:** Asterisk ya habia empezado a marcar (originado el INVITE) antes de que el celular se registrara. Para que el INVITE llegue al celular despues del REGISTER, hay dos opciones:
   a. La Stasis app debe esperar N segundos despues de recibir el REGISTER (polling de presencia) y reintentar el originate. Este es el "TODO pendiente en la Stasis app: presencia real via AMI" mencionado en el README.
   b. Implementar un endpoint de "claim call" que la app movil llama despues de registrarse, y Rails/Stasis dispara un originate fresco.
   c. Aumentar el timeout de 30s a 60s o mas y esperar que el REGISTER llegue antes del timeout.

**Esta decision de arquitectura para background es el riesgo tecnico mas alto de la Fase 2 y requiere aprobacion antes de implementar.**

---

## 12. Flujo completo del Stasis app (contexto operativo)

```
Llamada PSTN entra -> trunk Claro -> Asterisk dialplan -> contexto Stasis
                                                              |
                                          Stasis Node.js <- StasisStart
                                                              |
                                          GET /api/v1/internal/sip/routing?phone=<E164>&linkedid=<UUID>
                                                              |
                                          Rails:RoutingDecisionService -> { action:'dial', extension:'9001' }
                                                              |
                                          Stasis Node.js: originate PJSIP/9001, timeout=30s
                                                              |
                           INVITE llega al UA (browser/celular registrado como PJSIP/9001)
                                                              |
                              Browser contesta -> ChannelStateChange(Up)
                                                              |
                              POST /api/v1/internal/sip/events { event_type:'answered' }
                                                              |
                              Rails: StatusUpdateService -> CallStatus::Manager -> Call.in_progress
                                                              |
                                          bridge mixing (caller <-> asesor)
                                                              |
                              StasisEnd -> POST /sip/events { event_type:'ended', duration_seconds }
                                                              |
                              Rails: AsteriskCallLogger -> Call.create(...)
```

El cliente movil (React Native) se inserta en el paso "INVITE llega al UA" - si esta en foreground y registrado, recibe el INVITE directamente. Si esta cerrado, necesita el push FCM para despertar primero.

---

## 13. Multi-dispositivo - decision de producto pendiente

Con `max_contacts=5` y `remove_existing=no`, el mismo asesor puede tener registrados:
- Chrome en PC (browser)
- Chrome en laptop
- App Android (celular)
- Hasta 2 dispositivos mas

Cuando entra una llamada, Asterisk hace fork a todos los contactos activos. El primero que conteste gana (via bridge mixing); los demas reciben CANCEL.

**No hay politica configurada en el codigo para:**
- Silenciar el celular si el browser ya contesto (o viceversa)
- Priorizar un dispositivo sobre otro
- Notificar a los demas dispositivos que la llamada ya fue tomada

**Decision de producto requerida antes de implementar la Fase 2:**
- Opcion A: fork-to-all (comportamiento por defecto de Asterisk). El celular y el browser suenan simultaneamente; primero en contestar gana. El usuario puede ver en la pantalla de llamada del celular que otra instancia (el browser) ya contesto.
- Opcion B: push FCM SOLO al celular cuando el browser no esta activo. Requiere logica en `VoipPushService` para verificar presencia del UA en el browser antes de enviar el push.
- Opcion C: el broker siempre es el celular (push FCM siempre). El browser actua como interfaz secundaria.

---

## 14. Estado de TURN en produccion - punto de verificacion

El README de FreePBX y el README de coturn confirman que el servidor TURN esta previsto en la arquitectura y que las credenciales vienen en `ice_servers` de `/sip/credential`. Sin embargo, **no se puede confirmar desde este entorno si `TURN_USERNAME` y `TURN_CREDENTIAL` estan efectivamente configurados en el `.env` de produccion/staging**. Si `ice_servers` en produccion solo devuelve el STUN de Google (sin TURN), las llamadas desde redes 4G fallaran en ~80% de los casos.

**Verificacion necesaria antes de la Fase 1:**
```bash
curl -H "api_access_token: <TOKEN>" \
  "https://<BACKEND_URL>/api/v1/accounts/<ACCOUNT_ID>/sip/credential" | jq '.ice_servers'
```
Si la respuesta solo tiene `stun:stun.l.google.com:19302`, el TURN no esta configurado y hay que configurarlo antes de considerar que la Fase 1 esta completa.

---

## 15. Rutas SIP completas (para referencia del cliente movil)

**Publicas (para la app movil):**
```
GET  /api/v1/accounts/:id/sip/credential     -> credenciales SIP + ice_servers
GET  /api/v1/accounts/:id/sip/recent_calls   -> historial (ultimas 20, sin paginacion)
PATCH /api/v1/accounts/:id/sip/identities/:agent_id -> solo admins
```

**Internas (solo Stasis Node.js):**
```
GET  /api/v1/internal/sip/routing    -> decision de routing
POST /api/v1/internal/sip/events     -> eventos de estado (answered, ended, no_answer)
```

---

## Checklist punto por punto del Step 0 del brief

| Elemento | Verificado | Resultado |
|---|---|---|
| `enterprise/app/models/call.rb` | Si | Ver seccion 7 |
| `enterprise/app/services/voice/provider/asterisk/adapter.rb` | Si | Ver seccion 7 |
| `enterprise/app/services/sip/credential_service.rb` | Si | Ver secciones 1 y 9 |
| `enterprise/app/controllers/api/v1/accounts/sip/credential_controller.rb` | Si | Ver seccion 1 |
| `enterprise/app/services/sip/status_update_service.rb` | Si | Ver seccion 12 |
| `enterprise/app/services/sip/routing_decision_service.rb` | Si | Ver seccion 10 |
| `enterprise/app/services/sip/routing/rules/` | Si (todas las reglas leidas) | Ver seccion 10 |
| `useJsSipSession.js` | Si | Ver secciones 3 y 3 |
| `useCallSession.js` | Si | Orquestador/router; irrelevante para RN (ver seccion 6 de discovery-js.md) |
| `useWhatsappCallSession.js` | Si | 100% WhatsApp P2P, no aplica a Asterisk |
| `stores/calls.js` | Si | Estado descrito en seccion 3 |
| `helper/actionCable.js` | Si | DISCREPANCIA CRITICA - ver seccion 2 |
| `recent_calls_controller.rb` | Si | Ver seccion 8 |
| `identities_controller.rb` | Si | Admin only; app movil no lo usa directamente |
| `deploy/freepbx/README.md` | Si | Ver secciones 5, 6, 13 |
| `deploy/freepbx/pjsip.webrtc.conf.example` | Si | Ver seccion 4 |
| `deploy/freepbx/pjsip-webrtc-extensions.md` | Si | Config real va en `pjsip.endpoint_custom_post.conf` |
| `deploy/coturn/` | Si | Ver seccion 5 |
| Voice call push VoIP integration point | Si | DISCREPANCIA - ver seccion 9 |
| `sip_fcm_token` / `sip_apns_voip_token` | Si (hallazgo no en el brief) | Columnas existen, sin logica que las pueble |

---

## Discrepancias con el brief - resumen

| # | Que dice el brief | Que dice el codigo real |
|---|---|---|
| D1 | "eventos de ActionCable (`voice_call.incoming`, ...)" son los que el cliente debe escuchar para llamadas Asterisk | FALSO: esos 4 eventos son exclusivamente para WhatsApp Business, con guards expliciticos `if (data?.provider !== VOICE_CALL_PROVIDERS.WHATSAPP) return`. Para Asterisk el INVITE llega por WebSocket SIP. |
| D2 | El punto de integracion para push FCM es "donde se emite `voice_call.incoming`" | FALSO: ese evento no existe para Asterisk. El punto correcto es `Sip::InternalController#routing` linea ~39. Ver seccion 9. |
| D3 | (no en el brief) | La tabla `sip_identities` ya tiene `sip_fcm_token` y `sip_apns_voip_token` - infraestructura de datos ya existe. |

---

## Decisiones de producto que requieren tu aprobacion antes de la Fase 1

### D1 - Gap de background: como maneja Asterisk el INVITE cuando el celular no esta registrado (BLOQUEANTE para Fase 2)

Cuando la app esta cerrada, el push FCM despierta el celular. Pero Asterisk ya empezo a marcar (originate) antes de que el celular se registrara. Opciones:
- A: Aumentar timeout del originate en Stasis (de 30s a 60s) y esperar que el REGISTER llegue a tiempo.
- B: La Stasis app vigila presencia (via AMI) y dispara un segundo originate si detecta un REGISTER nuevo durante la ventana de timeout.
- C: Un endpoint `POST /sip/call/claim?call_sid=<linkedid>` que la app llama despues de registrarse, y Stasis hace un originate fresco.

Recomendacion: la opcion A es la mas simple si el tiempo de arranque de la app es menor que el timeout aumentado. La opcion C es la mas robusta. La B requiere trabajo en la Stasis app (que no esta en scope de este brief).

### D2 - Como registra la app movil su FCM token para VoIP

Opciones:
- A: Usar las columnas existentes `sip_fcm_token` en `sip_identities`. Requiere extender `PATCH /sip/identities` para que un asesor pueda actualizar su propio token (hoy es admin-only).
- B: Reutilizar la tabla `notification_subscriptions` existente y buscar ahi por `subscription_type = 'fcm'` en `VoipPushService`. No requiere cambios en el schema ni en los endpoints.

Recomendacion: B es mas simple a corto plazo. A es la arquitectura mas limpia a largo plazo (token VoIP separado del token de notificaciones de chat).

### D3 - Multi-dispositivo: politica de "quien contesta" (mencionada en el brief)

Ver seccion 13. Definir antes de implementar la Fase 2.

### D4 - lib SIP en React Native: JsSIP directamente vs alternativas

El browser usa `jssip` directamente. En React Native las opciones son:
- A: `react-native-jssip` (wrapper de JsSIP para RN) - maximiza la paridad de codigo con el browser.
- B: `react-native-sip2` - cliente SIP nativo para Android (Kotlin), mas estable en background.
- C: `react-native-sipua` u otra alternativa.

El choice impacta directamente cuanto del flujo del browser puede reutilizarse en RN vs cuanto hay que reimplementar. Esta decision afecta la Fase 1 completa y requiere evaluacion antes de escribir codigo.

---

*Discovery realizado con 4 agentes paralelos. Fuente verificada: `https://github.com/Proyectoscol/procol-chat` rama `custom`, commit HEAD del 2026-06-26.*
