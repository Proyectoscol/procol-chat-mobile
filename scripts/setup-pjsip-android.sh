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
# - perl (para el configure de OpenSSL; viene con macOS)
#
# Uso:
#   export ANDROID_NDK_ROOT=/path/to/ndk
#   export ANDROID_HOME=/path/to/sdk   # opcional, default: brew
#   pnpm sip:setup

set -euo pipefail

PJSIP_VERSION="2.14.1"
PJSIP_URL="https://github.com/pjsip/pjproject/archive/refs/tags/${PJSIP_VERSION}.tar.gz"
PJSIP_DIR="/tmp/pjproject-${PJSIP_VERSION}"

OPENSSL_VERSION="3.4.1"
OPENSSL_URL="https://www.openssl.org/source/openssl-${OPENSSL_VERSION}.tar.gz"
OPENSSL_SRC="/tmp/openssl-${OPENSSL_VERSION}"
OPENSSL_OUT="/tmp/openssl-android-arm64-${OPENSSL_VERSION}"

OUTPUT_DIR="$(cd "$(dirname "$0")/.." && pwd)/android/app/libs"
AAR_NAME="pjsua2.aar"

echo "==> Setup PJSIP ${PJSIP_VERSION} + OpenSSL ${OPENSSL_VERSION} para Android"
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

# NDK toolchain host tag (NDK r25+ incluye binarios nativos para darwin-aarch64)
NDK_HOST_TAG=""
for tag in "darwin-aarch64" "darwin-arm64" "darwin-x86_64" "linux-x86_64"; do
  if [[ -d "$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/$tag/bin" ]]; then
    NDK_HOST_TAG="$tag"
    break
  fi
done
if [[ -z "$NDK_HOST_TAG" ]]; then
  echo "ERROR: No se encontro toolchain LLVM en el NDK."
  exit 1
fi
NDK_TOOLCHAIN_BIN="$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/$NDK_HOST_TAG/bin"
echo "  NDK toolchain: $NDK_HOST_TAG"

mkdir -p "$OUTPUT_DIR"

# ── Compilar OpenSSL para Android arm64-v8a ───────────────────────────────────
# DTLS-SRTP en PJMEDIA requiere OpenSSL. Sin esto, PJMEDIA_SRTP_HAS_DTLS=0
# y PJSIP solo soporta SDES, que rechaza UDP/TLS/RTP/SAVPF (WebRTC/Asterisk).
echo ""
echo "==> OpenSSL ${OPENSSL_VERSION} para Android arm64-v8a"

if [[ -f "$OPENSSL_OUT/lib/libssl.a" ]]; then
  echo "    (ya compilado, usando cache en $OPENSSL_OUT)"
else
  OPENSSL_TARBALL="/tmp/openssl-${OPENSSL_VERSION}.tar.gz"
  if [[ ! -f "$OPENSSL_TARBALL" ]]; then
    echo "    Descargando OpenSSL ${OPENSSL_VERSION}..."
    curl -L "$OPENSSL_URL" -o "$OPENSSL_TARBALL"
  fi
  if [[ ! -d "$OPENSSL_SRC" ]]; then
    cd /tmp
    tar -xzf "$OPENSSL_TARBALL"
  fi

  cd "$OPENSSL_SRC"
  echo "    Configurando OpenSSL para android-arm64..."
  # no-shared: OpenSSL se linka estaticamente en libpjsua2.so (no necesita .so separados en el APK)
  # no-tests: evita compilar los test binaries (reduce tiempo de build ~50%)
  PATH="$NDK_TOOLCHAIN_BIN:$PATH" \
    ./Configure android-arm64 no-shared no-tests \
      -D__ANDROID_API__=24 \
      --prefix="$OPENSSL_OUT" \
      --libdir=lib 2>&1 | tail -5

  echo "    Compilando OpenSSL (esto tarda ~5 min)..."
  PATH="$NDK_TOOLCHAIN_BIN:$PATH" \
    make -j"$(sysctl -n hw.logicalcpu 2>/dev/null || nproc)" 2>&1 | tail -5

  echo "    Instalando headers y libs..."
  PATH="$NDK_TOOLCHAIN_BIN:$PATH" \
    make install_sw 2>&1 | tail -5
fi

# Verificacion: headers y libs deben existir
if [[ ! -f "$OPENSSL_OUT/include/openssl/ssl.h" ]] || [[ ! -f "$OPENSSL_OUT/lib/libssl.a" ]]; then
  echo "ERROR: OpenSSL no se instalo correctamente."
  echo "  Esperado: $OPENSSL_OUT/include/openssl/ssl.h"
  echo "  Esperado: $OPENSSL_OUT/lib/libssl.a"
  exit 1
fi
echo "    OpenSSL OK: $OPENSSL_OUT"

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

/* Opus requiere libopus compilado para Android - Asterisk transcodea en el servidor */
#define PJMEDIA_HAS_OPUS_CODEC 0

/* No necesitamos video */
#define PJMEDIA_HAS_VIDEO 0

/* Solo codecs requeridos (PCMA/PCMU vienen habilitados por defecto) */
#define PJMEDIA_HAS_G722_CODEC 0
#define PJMEDIA_HAS_G7221_CODEC 0
#define PJMEDIA_HAS_ILBC_CODEC 0
#define PJMEDIA_HAS_SPEEX_CODEC 0

/* SRTP con DTLS obligatorio. configure-android pone PJ_HAS_SSL_SOCK=1 en os_auto.h
   pero PJMEDIA_SRTP_HAS_DTLS tiene default 0 en config.h y NO lo sobreescribe en
   config_auto.h — hay que forzarlo aqui explicitamente. */
#define PJMEDIA_HAS_SRTP 1
#define PJMEDIA_SRTP_HAS_DTLS 1
EOF

# ── Compilar nativo (configure + make) ───────────────────────────────────────
echo ""
echo "==> Compilando PJSIP para arm64-v8a (esto tarda 15-25 min)..."

export APP_PLATFORM=android-24

# Limpiar config previa (necesario para que --with-ssl tome efecto aunque el dir exista)
make distclean 2>/dev/null || true

# Configure con OpenSSL: activa PJMEDIA_SRTP_HAS_DTLS y soporte WSS/TLS nativo
CONFIGURE_LOG="/tmp/pjsip-configure-$(date +%Y%m%d-%H%M%S).log"
./configure-android --use-ndk-cflags --with-ssl="$OPENSSL_OUT" 2>&1 | tee "$CONFIGURE_LOG" | tail -10

echo ""
echo "==> Verificando deteccion SSL en configure..."
SSL_LINE=$(grep -i "ssl\|openssl\|dtls\|tls" "$CONFIGURE_LOG" | grep -v "^#\|config_site\|pjsip/include" | head -20)
if [[ -n "$SSL_LINE" ]]; then
  echo "  Lineas relevantes del configure:"
  echo "$SSL_LINE"
else
  echo "  ADVERTENCIA: No se encontraron lineas SSL/DTLS en la salida del configure."
  echo "  Log completo guardado en: $CONFIGURE_LOG"
fi

# Verificar tambien que config_auto.h habilito DTLS
CONFIG_AUTO="pjmedia/include/pjmedia/config_auto.h"
if [[ -f "$CONFIG_AUTO" ]]; then
  DTLS_VAL=$(grep "PJMEDIA_SRTP_HAS_DTLS" "$CONFIG_AUTO" 2>/dev/null || echo "  (no encontrado)")
  echo "  $CONFIG_AUTO:"
  echo "    $DTLS_VAL"
fi

echo ""
echo "==> make dep..."
make dep 2>&1 | tail -5

echo ""
echo "==> make (compilando librerias nativas)..."
make -j"$(sysctl -n hw.logicalcpu 2>/dev/null || nproc)" 2>&1 | tail -20

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

# make java ya coloca libpjsua2.so en jniLibs directamente.
# El find excluye jniLibs para evitar que cp falle al copiar un archivo sobre si mismo.
find . -name "libpjsua2.so" -not -path "*/build/*" -not -path "*/jniLibs/*" 2>/dev/null | while IFS= read -r so; do
  cp "$so" "$JNILIB_DIR/"
  echo "  Copiado: $so -> $JNILIB_DIR/"
done
if [[ -f "$JNILIB_DIR/libpjsua2.so" ]]; then
  echo "  libpjsua2.so OK: $JNILIB_DIR/"
else
  echo "ERROR: libpjsua2.so no encontrado en $JNILIB_DIR"
  exit 1
fi

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
echo "Verificar que DTLS-SRTP esta disponible en el build:"
echo "  adb logcat -s SipEngine SipAccount SipService | grep -i 'DTLS\|dtls\|keying'"
echo "  Debe aparecer: 'SRTP uses keying method DTLS-SRTP'"
echo "  NO debe aparecer: 'PJMEDIA_SRTP_ESDPINTRANSPORT'"
echo ""
echo "Siguiente paso: compilar la app Android:"
echo "  pnpm run:android"
echo ""
echo "Verificar registro SIP:"
echo "  adb logcat -s SipEngine SipAccount SipService"
