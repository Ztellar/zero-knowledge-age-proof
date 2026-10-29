# Plan: prueba de edad post-cuántica con la cédula chilena

Fecha: 2026-10-22.

Estado: **etapas 1 a 5 hechas** (2026-10-29). La app lee la cédula real, genera la prueba
STARK en el teléfono con Active Authentication del chip, y el verificador web la acepta.
Qué hace hoy el proyecto, sus límites y cómo correrlo: [README](../README.md).

## Objetivo

Proyecto de portfolio, open source y educativo
([decisión 0003](decisions/0003-android-app-post-quantum-proof.md)). Primera versión: una
app Android que lee la cédula chilena por NFC, comprueba la firma del Registro Civil y
genera en el teléfono una prueba STARK de "mayor de 18", sin que ningún dato salga del
teléfono.

## Qué es post-cuántico y qué no

La tabla vigente está en el [README](../README.md#qué-es-post-cuántico-y-qué-no).

## Qué prueba la prueba

Entradas privadas: DG1 (los datos del MRZ), el SOD (los hashes firmados y su firma) y el
certificado DSC. Entradas públicas: la fecha del día D y la clave de una CSCA chilena.
Salida: "mayor de 18 en D". Diseño inicial; la lista vigente de comprobaciones, con la clave
del teléfono y la Active Authentication de la decisión 0006, está en el
[README](../README.md#cómo-funciona).

El circuito comprueba:

1. el DSC está firmado por la CSCA (RSA);
2. el SOD está firmado por el DSC (RSA);
3. el hash de DG1 coincide con el que trae el SOD;
4. con la fecha de nacimiento de DG1, la persona tiene 18 años o más en D;
5. el documento no está vencido en D.

Trampas conocidas:
- **El año de nacimiento viene con dos dígitos** (`YYMMDD`): el siglo se resuelve contra D.
- **D es entrada pública**, y quien verifica rechaza fechas viejas.
- **Esquema de firma:** RSA PKCS#1 v1.5, confirmado con la cédula real en la etapa 3.

## Stack

- **App:** Kotlin nativo. Lectura NFC con [JMRTD](https://jmrtd.org) (Java, maduro,
  soporta BAC y PACE).
- **Núcleo criptográfico:** Rust, llamado desde Kotlin por JNI escrito a mano
  ([`prover/ffi`](../prover/ffi/src/lib.rs)), no UniFFI.
- **Sistema de pruebas, candidato:** [RISC Zero](https://github.com/risc0/risc0)
  (Apache-2.0). Se programa en Rust normal y tiene aceleración para RSA y SHA-256. Su
  prueba STARK no debe envolverse en Groth16, que no es post-cuántico. **Alternativa** si
  no corre en Android: Plonky3, con el circuito escrito a mano.
- **Builds:** locales con Gradle. **No usa EAS.**

## Etapas

Cada etapa tiene un criterio de salida. Si no se cumple, se revisa el plan antes de seguir.

1. **Medición en el computador.** Datos de cédula de prueba: una CSCA y un DSC de prueba
   con RSA 4096, y un SOD y un DG1 armados como los de una cédula TD1. Prueba STARK de los
   cinco puntos.
   *Salida:* tiempo, RAM y tamaño de la prueba en el M1 Pro, en una tabla.
   **Hecha:** prueba sucinta en 32,8 s con 2,57 GB después de optimizar (al principio, 151,6 s
   con 6,6 GB); pesa 223.514 bytes
   ([mediciones](measurements/stage-1-desktop.md)).
2. **El mismo probador en Android.** Compilar para `aarch64-linux-android` y correrlo en el
   S25.
   *Salida:* tiempo y RAM en el teléfono. Si no compila o no cabe en memoria, se pasa a
   Plonky3.
   **Hecha:** compila sin cambios; prueba sucinta en 64,7 s con 2,34–2,47 GB en el S25
   ([mediciones](measurements/stage-2-android.md)). No hace falta Plonky3.
3. **Lectura de la cédula real.** App Kotlin con JMRTD: MRZ escrito a mano, lectura de DG1 y
   del SOD, y comprobación de la firma en Kotlin (sin conocimiento cero todavía).
   *Salida:* la app muestra "firma válida" con tu cédula y confirma el esquema de firma.
   **Hecha** (2026-10-25), con una cédula real en el S25: los
   cuatro chequeos pasan. Código en [`android/`](../android); 10 tests del verificador con
   la cédula de prueba. Esquema real, leído de la pantalla de la app:

   | Campo | Cédula real | Cédula de prueba (etapas 1–2) |
   | --- | --- | --- |
   | Acceso al chip | PACE | — |
   | Firma del SOD | SHA-256 con RSA PKCS#1 v1.5 | igual |
   | Clave del DSC | RSA 2048 | RSA 4096 |
   | CSCA que firmó el DSC | CHL 2021, RSA 3072 | RSA 4096 |
   | Hash de los data groups | SHA-256 | igual |
   | Tamaño DG1 / SOD | 95 B / 2.177 B | 95 B / 2.261 B |

   Las mediciones de las etapas 1 y 2 usaron claves más grandes que las reales, así que
   sobreestiman el costo de las firmas. Las claves CSCA salen del registro de ZKPassport,
   que trae sólo módulo y exponente; por eso el core recibe la CSCA como clave (paso 1
   de la etapa 4).
4. **Integración.** La app lee la cédula y genera la prueba en el teléfono, una sola vez
   y en segundo plano. La prueba incluye en su journal una clave pública ML-DSA del
   teléfono ([decisión 0004](decisions/0004-prove-once-present-many.md)).
   *Salida:* prueba generada a partir de una cédula real, sin red, y guardada en la app.
   Antes de partir: la cédula de prueba ahora usa los tamaños de clave reales (CSCA RSA
   3072, DSC RSA 2048); en el S25 la prueba sucinta tarda 58–65 s con 2,46 GB
   ([tamaños de clave y medición](measurements/chile-key-sizes.md)).
   **Paso 1 hecho** (2026-10-26): el core recibe la CSCA como clave (SubjectPublicKeyInfo
   DER) en vez de certificado, y el `Claim` publica su SHA-256; acepta el SOD tal como
   viene del chip (tag 0x77). Un test comprueba que la clave que arma Kotlin desde módulo y
   exponente es idéntica byte a byte a la de OpenSSL.
   **Paso 3 hecho** (2026-10-26): el probador corre dentro de la app como librería JNI
   ([`prover/ffi`](../prover/ffi/src/lib.rs)). **Primera prueba STARK a partir de una
   cédula real** en el S25: 51,5 s, 223.514 B, verificada en el
   teléfono, con el `Claim` apuntando a la CSCA CHL 2021. La app llegó a `VmHWM`
   2.476.448 kB (2,54 GB) y Android no la cerró; una sola corrida, con la app en primer
   plano y la pantalla encendida.
   **Servicio en segundo plano hecho** (2026-10-26): la prueba corre en un servicio en
   primer plano de tipo `specialUse` ([`ProverService`](../android/app/src/main/kotlin/cl/zkage/app/ProverService.kt))
   con un wake lock, una notificación con cronómetro y otra al terminar. Probado con la
   cédula real saliendo de la app: terminó en **135 s** (el monitor de `adb` vio el servicio
   activo de 09:16:49 a 09:19:05, 136 s), con `VmHWM` 2.588.748 kB (2,59 GB) y sin cierres.
   Tarda 2,6 veces más que en la actividad (135 / 51,5). Causa probable, **sin verificar**:
   en este teléfono el cpuset `foreground` (el de un servicio en primer plano) usa los
   núcleos 0–5 (3,53 GHz) y deja fuera los dos de 4,47 GHz que sí tiene `top-app`
   (0–7); el cpuset `background` usa sólo 0–1 y 4–5. No se midió en qué cpuset quedó el
   proceso durante la corrida. Una sola corrida; no se confirmó que tocar la notificación
   abra el resultado.
   **Clave ML-DSA hecha** (2026-10-26): el journal publica `device_key_sha256`, el SHA-256
   de la clave pública ML-DSA-65 del teléfono; dónde vive la clave está en la
   [decisión 0004](decisions/0004-prove-once-present-many.md). El programa sube de 157.578 a
   161.167 ciclos (+3.589) y sigue en un segmento. Al terminar, la app firma un desafío
   aleatorio y lo verifica contra el hash de la prueba. Primera corrida en el S25 con la
   cédula real: prueba de 223.770 B en 94,2 s (no se registró si la app estaba en
   pantalla), clave en software con la semilla cifrada por una clave AES en StrongBox,
   firma de 3.309 B en 116 ms (una sola medición, incluye la primera carga de clases).
   **Android 17** (2026-10-26): emulador API 37 arm64 con páginas de 16 KB y 8 GB de RAM.
   La librería ya está alineada a 16 KB (`LOAD` con `0x4000`, NDK r30) y la prueba de la
   cédula de prueba corre dentro del proceso de la app en 100,9 s con `VmHWM` 2.618.208 kB
   (test instrumentado `NativeProverTest`). Android 17 agrega un límite de memoria por app
   según la RAM del teléfono (`MemoryLimiter:AnonSwap`); en el emulador está desactivado,
   así que **no se sabe si los ~2,6 GB caben bajo el límite del S25**. La clave ML-DSA y la
   migración desde software están en la decisión 0004. Los tests instrumentados borran
   claves y desinstalan la app: correrlos sólo con `ANDROID_SERIAL` apuntando a un
   emulador. La app sigue con `targetSdk` 36; subirlo a 37 exige, entre otras cosas, el
   permiso de red local si la etapa 5 habla con el verificador por Wi-Fi.
   **Falta:** la librería pesa 87 MB y la APK de debug 134 MB.
5. **Presentación, verificador y documentación.** El verificador manda un desafío y el
   teléfono responde con la prueba guardada y una firma ML-DSA. Verificador (CLI o web) y
   README con el mapa post-cuántico, las mediciones, la limitación de enlazabilidad de la
   0004 y un artículo corto.
   Transporte: servidor web con QR ([decisión 0005](decisions/0005-web-verifier-with-qr.md)).
   **Librería del verificador hecha** (2026-10-26,
   [`prover/verifier`](../prover/verifier/src/lib.rs)): comprueba el recibo contra el image
   ID, la CSCA, la edad mínima, la vigencia (12 meses por defecto), el hash de la clave del
   teléfono y la firma ML-DSA-65 de `"zkage-presentation-v1" || origen || desafío`. Tests con
   un recibo de la cédula de prueba ligado a una clave de demo de semilla fija; un rechazo
   por cada comprobación. Verificar el recibo tarda 12,52 ms en el Mac (una medición).
   Bouncy Castle y el crate `ml-dsa` derivan la misma clave de la misma semilla (test en
   ambos lados).
   **Verificador web hecho** (2026-10-26): lista de CSCAs de Chile en Rust generada por
   `gen_csca.py --rust` (comparada con Kotlin en un test), servidor
   [`prover/verifier-web`](../prover/verifier-web/src/main.rs) con QR y certificado fijado,
   y en la app escaneo del QR, confirmación del origen y envío. Con la cédula de prueba y
   `curl`: aceptada en 30 ms ida y vuelta; firma para otro origen y segunda respuesta,
   rechazadas. **Con la cédula real en el S25: aceptada** ("Mayor de 18, cédula firmada
   por CHL 2021 RSA3072, prueba del 2026-10-26"). Demo:
   `adb reverse tcp:8443 tcp:8443` y `cargo run --release -p verifier-web -- --host 127.0.0.1`.
   **Active Authentication comprobada fuera de la prueba** (2026-10-26,
   [`ActiveAuth.kt`](../android/verifier/src/main/kotlin/cl/zkage/verifier/ActiveAuth.kt)):
   la cédula real trae DG14 y DG15; DG15 coincide con el SOD y es una clave P-256
   (prime256v1) con ecdsa-plain-SHA256; el chip firmó un desafío aleatorio y la firma es
   válida.
   **Active Authentication dentro de la prueba hecha** (2026-10-26,
   [decisión 0006](decisions/0006-active-authentication-in-proof.md)): el chip firma el
   inicio del hash de la clave del teléfono y el programa lo comprueba. Duplica el tiempo de
   la prueba; el tamaño del segmento se elige según la RAM del teléfono
   ([medición](measurements/active-auth.md)). **Con la cédula real en el S25**
   (2026-10-29): "Chip original (Active Authentication, P-256): sí", 92,5 s con la app
   abierta y segmento de 2^19, 223.778 B; el verificador web la aceptó ("Mayor de 18,
   cédula firmada por CHL 2021 RSA3072, prueba del 2026-10-29, con el chip de la cédula
   presente").
   **README hecho** (2026-10-29): mapa post-cuántico, mediciones, límites y demo.

## Riesgos

| Riesgo | Qué pasa si se cumple |
| --- | --- |
| RISC Zero no compila o no corre en Android | Descartado en la etapa 2: corre en el S25 |
| Android 17 mata la app por su límite de memoria por app mientras prueba | Sin medir: el emulador no aplica el límite; se verá en el S25 con Android 17 (`am memory-limiter status`) |
| Generar la prueba tarda minutos en el teléfono | Con Active Authentication tarda 92,5 s en la app y 128,6 s por `adb` en el S25; con poca RAM la app usa segmentos más chicos (366 s con 2^17 en el S25). Gama media o antigua sin medir |
| La prueba pesa demasiado para un QR | El verificador la recibe por archivo o por red en la primera versión |
| Leer datos reales de cédulas en tests | Los tests usan sólo documentos de prueba; la cédula real sólo en el teléfono de desarrollo |

## Antecedentes

### Certificados de Chile

Verificado el 2026-10-22 contra el
[registro público de certificados de ZKPassport](https://github.com/zkpassport/zkpassport-packages/blob/main/packages/registry-explorer/public/assets/certificates/certificates.json):
4 CSCA con `country: CHL` (de 411 certificados y 120 países), sacadas de las Master Lists de
ICAO, Alemania y Países Bajos. Todas RSA con SHA-256.

| Vigente desde | Clave | Firma documentos hasta | Fuentes |
| --- | --- | --- | --- |
| 2024-05-28 | RSA 4096 | 2029-05-28 | ICAO, NL |
| 2021-08-01 | RSA 3072 | 2026-08-01 | ICAO, DE, NL |
| 2016-08-01 | RSA 3072 | — | ICAO, DE, NL, IT |
| 2013-08-01 | RSA 3072 | — | ICAO, DE, NL, ES |

### Prueba con ZKPassport (2026-10-22)

La app de ZKPassport acepta una cédula real en un Samsung S25. No pide cuenta,
compara la cara con la foto del chip y permite varios documentos por teléfono. En el S25 fue
casi inmediata, sin tiempos medidos.

### Lo que ZKPassport ya resuelve

Revisado el 2026-10-22 en
[zkpassport-docs](https://github.com/zkpassport/zkpassport-docs) (commit `a41aa5f`) y
[zkpassport-packages](https://github.com/zkpassport/zkpassport-packages) (commit `fac2812`).
En esa revisión no se encontró el código de la app, y lo que hace se infirió de la
documentación y del SDK. **Corrección (2026-10-29):** la app sí está publicada, en
[mobile-app](https://github.com/zkpassport/mobile-app) (Apache-2.0, un solo push el
2026-07-17, commit `c52f5ef`). Su comparación de cara se revisa en la
[decisión 0007](decisions/0007-face-match.md).

| Lo que diseñamos (0001, 0002) | ZKPassport |
| --- | --- |
| Un carnet, un teléfono | No hay registro de teléfonos. Cada servicio recibe un identificador único por documento, igual desde cualquier teléfono, y puede pedir comparación de cara en **cada** verificación |
| Código con OPRF | Ya existe: "identificadores salados" con vOPRF, el secreto repartido en varios servidores. Exige comparación de cara |
| Comparación de cara en el teléfono | Ya existe, con prueba de vida. Exige atestación del dispositivo y puede negarse en algunos Android |
| Revalidación anual | La prueba del vencimiento vale por defecto 7 días (`validity`) |
| Funciona en gama baja | Según su FAQ: con RSA usa hasta 1 GB de RAM; pruebas base de 10 a 50 s una vez y de menos de 1 a 10 s por verificación. No medido por nosotros |
| QR en persona y sin internet | No encontrado: el sitio muestra el QR, la app lo escanea y la respuesta pasa por un servidor puente (`@obsidion/bridge`) |
| Sistema de pruebas | UltraHonk sobre Barretenberg: curvas elípticas, **no post-cuántico** |

### Fuera de la primera versión

- Todo lo de las decisiones 0001 y 0002, en pausa.
