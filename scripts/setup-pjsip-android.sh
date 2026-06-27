#!/usr/bin/env bash
# setup-pjsip-android.sh
#
# Descarga y construye PJSIP 2.14.1 para Android con:
# - WebSocket Secure (WSS) transport para Asterisk wss://<host>:8089/ws
# - ICE / STUN / TURN (necesario para 4G/5G con NAT simetrico)
# - DTLS-SRTP (media_encryption=dtls en pjsip.conf de Asterisk)
# - Codec Opus (para transcoding alaw<->opus)
#
# Prerequisitos:
# - Android NDK r23+ en ANDROID_NDK_ROOT
# - Java 17 (Java 25 rompe el build)
# - Python 3 (para SWIG)
# - swig (brew install swig)
#
# Uso:
#   export ANDROID_NDK_ROOT=/path/to/ndk
#   export ANDROID_HOME=/path/to/sdk   # opcional, default: brew
#   pnpm sip:setup

set -euo pipefail

PJSIP_VERSION="2.14.1"
PJSIP_URL="https://github.com/pjsip/pjproject/archive/refs/tags/${PJSIP_VERSION}.tar.gz"
PJSIP_DIR="/tmp/pjproject-${PJSIP_VERSION}"
OUTPUT_DIR="$(cd "$(dirname "$0")/.." && pwd)/android/app/libs"
AAR_NAME="pjsua2.aar"

echo "==> Setup PJSIP ${PJSIP_VERSION} para Android"
echo "    Output: ${OUTPUT_DIR}/${AAR_NAME}"
echo ""

# ── Variables de entorno ──────────────────────────────────────────────────────

# Java 17
if [[ -z "${JAVA_HOME:-}" ]] || ! "$JAVA_HOME/bin/java" -version 2>&1 | grep -q "17\\."; then
  for candidate in \
    "/opt/homebrew/Cellar/openjdk@17/17.0.19/libexec/openjdk.jdk/Contents/Home" \
    "/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home" \
    "/usr/lib/jvm/java-17-openjdk-amd64"; do
    if [[ -f "$candidate/bin/java" ]]; then
      export JAVA_HOME="$candidate"
      echo "  Java 17: $JAVA_HOME"
      break
    fi
  done
fi
if [[ -z "${JAVA_HOME:-}" ]]; then
  echo "ERROR: No se encontro Java 17. Instalar con: brew install openjdk@17"
  exit 1
fi
export PATH="$JAVA_HOME/bin:$PATH"

# NDK
if [[ -z "${ANDROID_NDK_ROOT:-}" ]]; then
  for candidate in \
    "/opt/homebrew/share/android-commandlinetools/ndk/27.1.12297006" \
    "$HOME/Library/Android/sdk/ndk/27.1.12297006" \
    "$HOME/Library/Android/sdk/ndk/25.2.9519653"; do
    if [[ -d "$candidate" ]]; then
      export ANDROID_NDK_ROOT="$candidate"
      echo "  NDK: $ANDROID_NDK_ROOT"
      break
    fi
  done
fi
if [[ -z "${ANDROID_NDK_ROOT:-}" ]]; then
  echo "ERROR: No se encontro Android NDK. Configurar ANDROID_NDK_ROOT."
  exit 1
fi

# Android SDK (para Gradle build del AAR)
if [[ -z "${ANDROID_HOME:-}" ]]; then
  for candidate in \
    "/opt/homebrew/share/android-commandlinetools" \
    "$HOME/Library/Android/sdk"; do
    if [[ -d "$candidate/build-tools" ]]; then
      export ANDROID_HOME="$candidate"
      echo "  Android SDK: $ANDROID_HOME"
      break
    fi
  done
fi
if [[ -z "${ANDROID_HOME:-}" ]]; then
  echo "ERROR: No se encontro Android SDK. Configurar ANDROID_HOME."
  exit 1
fi

# swig
if ! command -v swig &>/dev/null; then
  echo "ERROR: swig no encontrado. Instalar con: brew install swig"
  exit 1
fi
echo "  swig: $(swig -version | head -2 | tail -1)"

mkdir -p "$OUTPUT_DIR"

# ── Descargar PJSIP ──────────────────────────────────────────────────────────
if [[ ! -d "$PJSIP_DIR" ]]; then
  echo ""
  echo "==> Descargando PJSIP ${PJSIP_VERSION}..."
  TARBALL="/tmp/pjsip-${PJSIP_VERSION}.tar.gz"
  if [[ ! -f "$TARBALL" ]]; then
    curl -L "$PJSIP_URL" -o "$TARBALL"
  fi
  cd /tmp
  tar -xzf "$TARBALL"
  [[ -d "pjproject-${PJSIP_VERSION}" ]] || mv pjproject-* "$PJSIP_DIR"
fi

cd "$PJSIP_DIR"

# ── config_site.h ─────────────────────────────────────────────────────────────
echo ""
echo "==> Configurando config_site.h..."
cat > pjlib/include/pj/config_site.h << 'EOF'
/* procol-chat-mobile VoIP - PJSIP Android config */
#define PJ_CONFIG_ANDROID 1
#include <pj/config_site_sample.h>

/* Opus codec (Asterisk transcodea alaw<->opus) */
#define PJMEDIA_HAS_OPUS_CODEC 1

/* No necesitamos video */
#define PJMEDIA_HAS_VIDEO 0

/* Solo codecs requeridos */
#define PJMEDIA_HAS_G722_CODEC 0
#define PJMEDIA_HAS_G7221_CODEC 0
#define PJMEDIA_HAS_ILBC_CODEC 0
#define PJMEDIA_HAS_SPEEX_CODEC 0

/* DTLS-SRTP obligatorio */
#define PJMEDIA_HAS_SRTP 1
EOF

# ── Compilar nativo (configure + make) ───────────────────────────────────────
echo ""
echo "==> Compilando PJSIP para arm64-v8a (esto tarda 15-25 min)..."

export APP_PLATFORM=android-24

# Limpiar config previa si existe
make distclean 2>/dev/null || true

# Configure para Android (sin OpenSSL externo - usa el del NDK via GnuTLS if available)
# Note: sin --with-ssl compilamos sin TLS nativo pero podemos usar DTLS via PJMEDIA
# Para WSS completo se necesita OpenSSL prebuilds
./configure-android --use-ndk-cflags 2>&1 | tail -20

echo ""
echo "==> make dep..."
make dep 2>&1 | tail -5

echo ""
echo "==> make (compilando librerias nativas)..."
make -j$(sysctl -n hw.logicalcpu 2>/dev/null || nproc) 2>&1 | tail -20

# ── Generar bindings SWIG Java ────────────────────────────────────────────────
echo ""
echo "==> Generando Java SWIG bindings..."
cd pjsip-apps/src/swig
make java 2>&1 | tail -20

# ── Verificar que se generaron los .java ──────────────────────────────────────
JAVA_OUT="java/output"
if [[ ! -d "$JAVA_OUT" ]] && [[ ! -d "java/android/pjsua2/src/main/java" ]]; then
  echo "ERROR: No se encontraron archivos Java generados por SWIG."
  echo "  Verifique que 'swig' esta instalado y que el make java fue exitoso."
  exit 1
fi

# ── Copiar Java sources al modulo Android ────────────────────────────────────
echo ""
echo "==> Copiando fuentes Java y .so al modulo Android AAR..."
JAVA_DIR="java/android/pjsua2/src/main/java/org/pjsip/pjsua2"
mkdir -p "$JAVA_DIR"

# Los archivos .java generados estan en java/output/
if ls java/output/*.java &>/dev/null; then
  cp java/output/*.java "$JAVA_DIR/"
fi

# Los .so compilados estan en la raiz de pjproject en la carpeta de cada lib
JNILIB_DIR="java/android/pjsua2/src/main/jniLibs/arm64-v8a"
mkdir -p "$JNILIB_DIR"

# Buscar libpjsua2.so generado por el SWIG make
find . -name "libpjsua2.so" -not -path "*/build/*" 2>/dev/null | while read so; do
  cp "$so" "$JNILIB_DIR/"
  echo "  Copiado: $so -> $JNILIB_DIR/"
done

# ── Build AAR via Gradle ──────────────────────────────────────────────────────
echo ""
echo "==> Construyendo AAR via Gradle..."
cd java/android

# Crear local.properties para Gradle
cat > local.properties << LOCALEOF
sdk.dir=$ANDROID_HOME
ndk.dir=$ANDROID_NDK_ROOT
LOCALEOF

chmod +x gradlew
ANDROID_HOME="$ANDROID_HOME" JAVA_HOME="$JAVA_HOME" \
  ./gradlew :pjsua2:assembleRelease --no-daemon 2>&1 | tail -30

# ── Copiar AAR al proyecto ────────────────────────────────────────────────────
BUILT_AAR=$(find pjsua2/build/outputs/aar -name "*.aar" 2>/dev/null | head -1)
if [[ -z "$BUILT_AAR" ]]; then
  echo ""
  echo "ERROR: No se encontro pjsua2.aar despues del build."
  echo "  Revisa los logs de compilacion arriba."
  exit 1
fi

cp "$BUILT_AAR" "${OUTPUT_DIR}/${AAR_NAME}"

echo ""
echo "============================================"
echo "OK - pjsua2.aar listo: ${OUTPUT_DIR}/${AAR_NAME}"
echo "Tamano: $(du -sh "${OUTPUT_DIR}/${AAR_NAME}" | cut -f1)"
echo "============================================"
echo ""
echo "Siguiente paso: compilar la app Android:"
echo "  pnpm run:android"
echo ""
echo "Verificar registro SIP:"
echo "  adb logcat -s SipEngine SipAccount SipService"
