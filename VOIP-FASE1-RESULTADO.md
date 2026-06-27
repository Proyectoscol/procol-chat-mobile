# VoIP Fase 1 - Resultado de pruebas

> Documento de evidencia segun checklist de cierre Fase 1.
> Fecha: 2026-06-26

## Estado del build

| Componente | Estado | Evidencia |
|---|---|---|
| PJSIP 2.14.1 compilado para arm64-v8a | OK | `pjsua2.aar` 2.1MB en `android/app/libs/` |
| Java SWIG bindings generados | OK | 278 clases en `pjsua2.aar` |
| `build.gradle` Groovy syntax fix | OK | `fileTree(dir: "libs", ...)` |
| APK debug build | En progreso | ... |

**Nota sobre Opus (Fase 2):** PJSIP 2.14.1 requiere libopus como dependencia externa (no incluida en el tarball). Para Fase 1 (registro SIP), no es necesario. Se habilitara en Fase 2 instalando `libopus-dev` antes del build o usando una imagen Docker con todas las dependencias pre-instaladas.

---

## 1. Registro SIP

### 1.1 Verificacion de credenciales en backend

- **Resultado:** CONFIRMADO - el usuario tiene `SipIdentity` con extension asignada en el backend (verificado por el usuario directamente).
- **Endpoint:** `GET /api/v1/accounts/:id/sip/credential` retorna credenciales validas.

### 1.2 Registro exitoso en logcat

**Log esperado:**
```
SipEngine: PJSIP endpoint started
SipEngine: SIP account created for 9001@<dominio>
SipAccount: Registration state changed: code=200
SipEngine: Registered as 9001
SipService: Broadcast sip.registered extension=9001
```

- **Resultado:** PENDIENTE - app en proceso de instalacion.

### 1.3 Confirmacion servidor Asterisk

Comando a ejecutar en el servidor Asterisk despues de confirmar registro:
```bash
asterisk -rx "pjsip show contacts"
```

Salida esperada:
```
Contact:  9001/sip:9001@<ip>:<puerto>  <Avail/...>
```

- **Resultado:** PENDIENTE

### 1.4 Perdida y recuperacion de red

- **Resultado:** PENDIENTE

---

## 2. Llamada con audio real

### 2.1 Llamada entrante

- **Resultado:** PENDIENTE

### 2.2 Llamada saliente

- **Resultado:** PENDIENTE

### 2.3 Audio en ambos sentidos

**Riesgo documentado:** Si `codec_opus.so` no esta instalado en el servidor Asterisk,
el audio fallara aunque la senalizacion SIP funcione. Verificar con:
```bash
asterisk -rx "module show like opus"
```

- **Resultado:** PENDIENTE

### 2.4 Negociacion de codec en Asterisk

- **Resultado:** PENDIENTE

### 2.5 Colgar desde celular

- **Resultado:** PENDIENTE

### 2.6 Colgar desde otro lado

- **Resultado:** PENDIENTE

---

## 3. Supervivencia en background

### 3.1 App minimizada 5 minutos

- **Resultado:** PENDIENTE

### 3.2 Llamada con app minimizada

- **Resultado:** PENDIENTE

### 3.3 App cerrada desde recientes

**Dispositivo de prueba:** Redmi 24049RN28L, Android 15, SDK 35

- **Resultado:** PENDIENTE - este caso define urgencia del push FCM de respaldo (D1/D2).

### 3.4 Prueba en Redmi (Android 15) especificamente

- **Resultado:** PENDIENTE

### 3.5 Reinicio del servicio

- **Resultado:** PENDIENTE

---

## 4. Permisos y configuracion

### 4.1 Tipo de Foreground Service

El `AndroidManifest.xml` declara:
```xml
android:foregroundServiceType="microphone"
```

Para Android 14+ (API 34+), el tipo `phoneCall` requiere usar Android's `ConnectionService` API y el permiso `MANAGE_PHONE_CALLS`. Ya que PJSIP maneja las llamadas directamente (sin `ConnectionService`), el tipo `microphone` es el correcto.

Referencia: https://developer.android.com/about/versions/14/changes/fgs-types-required

- **Resultado:** CORRECTO - `microphone` es el tipo apropiado para PJSIP nativo sin ConnectionService.

### 4.2 Exclusion de optimizacion de bateria

- **Resultado:** No implementado - pendiente para onboarding de Fase 2.

### 4.3 Permisos de microfono

- **Resultado:** PENDIENTE - verificar en primera llamada.

---

## 5. Conocidos y decisiones para Fase 2

| Issue | Impacto | Plan |
|---|---|---|
| Opus no compilado | Sin audio con codecs opus en Asterisk | Construir libopus + recompilar PJSIP |
| `foregroundServiceType=microphone` en vez de `phoneCall` | Puede ser rechazado en Play Store o en Android 14+ | Cambiar a `phoneCall\|microphone` |
| Sin Request Battery Optimization exclusion | Xiaomi/Redmi puede matar el servicio | Agregar en onboarding |
| Background gap si servicio muere | Llamadas perdidas | Push FCM via `sip_fcm_token` (D1/D2) |

---

*Actualizando a medida que avanzan las pruebas.*
