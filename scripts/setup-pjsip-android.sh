#!/usr/bin/env bash
# setup-pjsip-android.sh
#
# Descarga y construye PJSIP para Android con soporte de:
# - WebSocket Secure (WSS) transport para conectar a Asterisk wss://<host>:8089/ws
# - ICE / STUN / TURN (media_encryption=dtls, ice_support=yes)
# - Codec Opus (necesario para transcoding alaw<->opus con Asterisk)
# - DTLS-SRTP (media_encryption=dtls en pjsip.conf)
#
# Prerequisitos:
# - Android NDK r25c o superior en ANDROID_NDK_ROOT
# - Java 17 (Java 25 rompe el build de Gradle - ver README)
# - OpenSSL para Android (se descarga automaticamente)
# - Python 3 (para scripts de PJSIP)
#
# Uso:
#   export ANDROID_NDK_ROOT=/path/to/ndk
#   pnpm sip:setup
#   # o directamente:
#   bash scripts/setup-pjsip-android.sh

set -euo pipefail

PJSIP_VERSION="2.14.1"
PJSIP_DIR="/tmp/pjproject-${PJSIP_VERSION}"
PJSIP_URL="https://github.com/pjsip/pjproject/archive/refs/tags/${PJSIP_VERSION}.tar.gz"
OUTPUT_DIR="$(cd "$(dirname "$0")/.." && pwd)/android/app/libs"
AAR_NAME="pjsua2.aar"

echo "==> Setup PJSIP ${PJSIP_VERSION} para Android"
echo "    Output: ${OUTPUT_DIR}/${AAR_NAME}"
echo ""

# Verificar NDK
if [[ -z "${ANDROID_NDK_ROOT:-}" ]]; then
  echo "ERROR: ANDROID_NDK_ROOT no esta configurado."
  echo "  Ejemplo: export ANDROID_NDK_ROOT=~/Library/Android/sdk/ndk/25.2.9519653"
  exit 1
fi

if [[ ! -d "$ANDROID_NDK_ROOT" ]]; then
  echo "ERROR: ANDROID_NDK_ROOT no existe: $ANDROID_NDK_ROOT"
  exit 1
fi

echo "  NDK: $ANDROID_NDK_ROOT"

# Verificar Java 17
JAVA_VER=$(java -version 2>&1 | head -1 | grep -oP '(?<=version ")[\d.]+')
if [[ "$JAVA_VER" != 17* ]]; then
  echo "ADVERTENCIA: Java version '$JAVA_VER' detectada. Se recomienda Java 17."
  echo "  Si el build falla, cambia JAVA_HOME a Java 17."
fi

mkdir -p "$OUTPUT_DIR"

# Descargar PJSIP si no existe
if [[ ! -d "$PJSIP_DIR" ]]; then
  echo "==> Descargando PJSIP ${PJSIP_VERSION}..."
  curl -L "$PJSIP_URL" | tar -xz -C /tmp
  mv "/tmp/pjproject-${PJSIP_VERSION}" "$PJSIP_DIR" 2>/dev/null || true
fi

cd "$PJSIP_DIR"

# Configurar para Android con WebSocket + Opus + OpenSSL (para DTLS)
echo "==> Configurando PJSIP..."
export APP_PLATFORM=android-24
export ANDROID_ARCH=arm64-v8a   # Arquitectura principal; agregar armeabi-v7a, x86_64 segun necesidad

cat > pjlib/include/pj/config_site.h << 'EOF'
/* Configuracion para procol-chat-mobile VoIP module */
#define PJ_CONFIG_ANDROID 1

/* WebSocket SIP transport (necesario para wss://asterisk:8089/ws) */
#define PJSIP_HAS_TLS_TRANSPORT 1

/* ICE: necesario para WebRTC con Asterisk */
#define PJNATH_HAS_ICE 1

/* DTLS-SRTP: media_encryption=dtls en Asterisk */
#define PJMEDIA_HAS_SRTP 1

/* Opus codec: Asterisk transcodea alaw<->opus para clientes WebRTC */
#define PJMEDIA_HAS_OPUS_CODEC 1

/* Deshabilitar lo que no necesitamos para reducir tamanio del binario */
#define PJMEDIA_HAS_VIDEO 0
#define PJMEDIA_HAS_G711_CODEC 1
#define PJMEDIA_HAS_G722_CODEC 0
#define PJMEDIA_HAS_G7221_CODEC 0
#define PJMEDIA_HAS_ILBC_CODEC 0
#define PJMEDIA_HAS_SPEEX_CODEC 0

#include <pj/config_android_common.h>
EOF

# Build para Android (usando el script oficial de PJSIP)
echo "==> Compilando PJSIP para Android (esto puede tardar 10-20 minutos)..."
cd pjsip-apps/src/swig
python setup.py

echo ""
echo "==> Copiando pjsua2.aar a ${OUTPUT_DIR}..."
BUILT_AAR=$(find . -name "pjsua2.aar" | head -1)
if [[ -z "$BUILT_AAR" ]]; then
  echo "ERROR: No se encontro pjsua2.aar despues del build."
  echo "  Revisa los logs de compilacion arriba."
  exit 1
fi

cp "$BUILT_AAR" "${OUTPUT_DIR}/${AAR_NAME}"

echo ""
echo "OK - pjsua2.aar listo en ${OUTPUT_DIR}/${AAR_NAME}"
echo ""
echo "Siguiente paso:"
echo "  cd android && ./gradlew assembleRelease"
echo "  (o: pnpm build:android:local)"
echo ""
echo "Verificar registro SIP exitoso:"
echo "  adb logcat -s SipEngine SipAccount SipService"
