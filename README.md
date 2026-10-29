# zero-knowledge-age-proof

**Tipo:** entrada al proyecto · **Estado:** primera versión funcionando en un Galaxy S25 ·
**Verificado:** 2026-10-29

Una app Android que lee la cédula chilena por NFC y genera en el teléfono una prueba de
conocimiento cero de "mayor de 18". Quien verifica la prueba sabe que la firmó el Registro
Civil, que la persona cumple la edad, que la cédula está vigente y que el chip estaba
presente. No ve el nombre, el RUN ni la fecha de nacimiento.

La prueba es un STARK de [RISC Zero](https://github.com/risc0/risc0), que sólo usa funciones
hash. Las presentaciones se firman con ML-DSA-65 (FIPS 204). Es un proyecto de portfolio
open source y educativo ([decisión 0003](docs/decisions/0003-android-app-post-quantum-proof.md)),
no un producto: [ZKPassport](https://github.com/zkpassport) y Self ya resuelven la verificación
de edad con documentos, con sistemas de pruebas que no son post-cuánticos.

## Cómo funciona

1. **Lectura.** La app lee DG1 (el MRZ), DG2 (la foto), DG15 y el SOD del chip por PACE, y le
   pide al chip que firme el inicio del hash de la clave del teléfono (Active Authentication).
2. **Comparación de cara.** Si la cédula trae foto, la app la compara con la cámara frontal,
   con una prueba de vida, antes de dejar generar la prueba. Es una comprobación local: no
   entra en la prueba y no le promete nada al verificador
   ([decisión 0007](docs/decisions/0007-face-match.md)).
3. **Prueba, una vez.** El probador corre en el teléfono, en un servicio en segundo plano,
   y guarda la prueba en el almacenamiento privado de la app
   ([decisión 0004](docs/decisions/0004-prove-once-present-many.md)).
4. **Presentación, muchas veces.** El verificador web muestra un QR con un desafío. La app lo
   escanea y envía la prueba guardada más una firma ML-DSA-65 sobre
   `"zkage-presentation-v1" || origen || desafío`
   ([decisión 0005](docs/decisions/0005-web-verifier-with-qr.md)).

Dentro de la prueba, el programa ([`prover/core/src/lib.rs`](prover/core/src/lib.rs))
comprueba:

1. que el DSC está firmado por la CSCA (RSA);
2. que el SOD está firmado por el DSC (RSA);
3. que el `messageDigest` cubre los hashes de los data groups;
4. que el hash de DG1 coincide con el del SOD;
5. que la persona tiene la edad mínima y la cédula está vigente en la fecha de la prueba;
6. que DG15 está en el SOD y la firma P-256 del chip cubre la clave del teléfono, si la
   cédula tiene Active Authentication
   ([decisión 0006](docs/decisions/0006-active-authentication-in-proof.md)).

Lo único que sale de la prueba es el `Claim`: hash de la CSCA, fecha, edad mínima, hash de la
clave ML-DSA del teléfono y `chip_authenticated`.

## Qué es post-cuántico y qué no

| Capa | Algoritmo | ¿Post-cuántico? |
| --- | --- | --- |
| Prueba de conocimiento cero | STARK sucinto de RISC Zero, sin envolver en Groth16 | Sí, porque sólo usa funciones hash. Es la pieza central |
| Firma de cada presentación | ML-DSA-65 (FIPS 204) | Sí. En el S25 la clave es de software, con la semilla cifrada por una clave AES de StrongBox. El Keystore con ML-DSA llega en Android 17 (API 37) |
| Firmas del Registro Civil (CSCA → DSC → SOD) | RSA 2048/3072/4096 con SHA-256 | **No**, y no depende de este proyecto |
| Active Authentication del chip | ECDSA P-256 | **No**: es la clave de la cédula |
| Canal con el verificador web | TLS de rustls con el proveedor `ring` y certificado autofirmado fijado por el QR | **No**: `ring` no trae ML-KEM, así que el intercambio de claves es clásico. Protege la privacidad del envío; la validez la dan la prueba y la firma |

Un computador cuántico que rompa RSA podría fabricar una cédula falsa y probar con ella.
Lo que no podría es fabricar una prueba sin pasar por una cédula: esa parte es la
post-cuántica.

## Medido

Los números son de una cédula de prueba, salvo la última fila. Cada fila es una sola
corrida; las condiciones están en [`docs/measurements/`](docs/measurements).

| Dónde | Qué | Tiempo | RAM máxima |
| --- | --- | --- | --- |
| Mac (Apple Silicon) | Prueba, segmento de 2^19 | 59,5 s | 2,62 GB |
| Galaxy S25, por `adb` | Prueba, segmento de 2^19 | 128,6 s | 4,41 GB |
| Galaxy S25, por `adb` | Prueba, segmento de 2^18 | 195,4 s | 2,47 GB |
| Galaxy S25, por `adb` | Prueba, segmento de 2^17 | 366,1 s | 1,52 GB |
| Galaxy S25, en la app | Prueba con la cédula real, segmento de 2^19 | 92,5 s | no medida |

- La prueba pesa 223.778 B. Verificarla tarda 12,5 ms en el Mac y unos 20 ms en el S25.
- El programa usa 379.900 ciclos. La firma P-256 del chip se lleva 217.412
  ([medición](docs/measurements/active-auth.md)).
- La app elige el segmento según la RAM del teléfono: el más grande cuyo pico quepa en el 40%
  ([`SegmentSize.kt`](android/app/src/main/kotlin/cl/zkage/app/SegmentSize.kt)). El 40% es
  un criterio, no una medición.

## Límites conocidos

- **Las presentaciones se pueden enlazar.** Todos los verificadores reciben la misma prueba y
  la misma clave pública, así que dos verificadores que junten sus datos saben que fue la
  misma persona, aunque no sepan quién ([decisión 0004](docs/decisions/0004-prove-once-present-many.md)).
- **La prueba demuestra que el chip estaba presente, no que lo usó su dueño.** La app
  compara la cara con la foto del chip, pero quien compile el código sin esa comprobación
  genera pruebas igual de válidas. Además, la prueba de vida detiene una foto, no un video ni
  una máscara ([decisión 0007](docs/decisions/0007-face-match.md)).
- **El ligado del chip a la clave del teléfono es de 64 bits**: el desafío de Active
  Authentication mide 8 bytes ([decisión 0006](docs/decisions/0006-active-authentication-in-proof.md)).
- **Sólo se probó en un teléfono**, un Galaxy S25 con 12 GB de RAM. Un teléfono de gama
  media o antiguo, o el límite de memoria por app de Android 17, pueden hacer que la prueba
  tarde mucho más o no termine: no se ha medido.
- **La APK de debug pesa 200 MB** con una compilación limpia. 104 MB son la librería del
  probador y la comparación de cara, y 39 MB los modelos de cara.
- **Las cédulas aceptadas son las chilenas**, contra las 4 CSCA de Chile en
  [`ChileCsca.kt`](android/verifier/src/main/kotlin/cl/zkage/verifier/ChileCsca.kt). Las
  claves vienen del registro público de ZKPassport.
- Lo que el diseño original pedía más allá de esto (una cédula por teléfono, revalidación
  anual) está en pausa ([decisión 0001](docs/decisions/0001-credential-without-personal-data.md),
  [decisión 0002](docs/decisions/0002-device-registry.md)).

## Primeros pasos

Probado en macOS con Apple Silicon. Necesita:

- Rust (rustup) y el toolchain de RISC Zero (`rzup`), que deja `r0vm` en `~/.risc0/bin`;
- JDK 17 para Gradle;
- para Android, el NDK de Homebrew (`android-ndk`) y `rustup target add aarch64-linux-android`.

**Verificación rápida** (unos 20 s en el Mac, sin teléfono):

```sh
cd prover
cargo test --release -p age-core -p age-verifier
```

Corre 28 tests con la cédula de prueba de
[`prover/fixtures/gen.py`](prover/fixtures/gen.py). No uses `RISC0_SKIP_BUILD=1` aquí: con
esa variable el image ID no es el del programa y los tests de presentación fallan con
`Err(Proof)`.

**Comparación de cara** (unos 3 minutos la primera vez, por la compilación): baja los modelos
y prueba con retratos de dominio público.

```sh
prover/scripts/download-face-models.sh
cd prover && cargo test --release -p facematch
```

**Tests de Kotlin**, el lector de cédula, la Active Authentication, ML-DSA, la foto del chip y
la elección de segmento (33 tests):

```sh
cd android
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :verifier:test :app:testDebugUnitTest
```

**Prueba en el computador** (alrededor de 1 minuto), con la cédula de prueba:

```sh
cd prover
cargo run --release -p host -- succinct
```

`execute` en vez de `succinct` sólo cuenta los ciclos.

## Demo con una cédula y un teléfono

1. Baja los modelos de cara, compila la librería del probador y la app, e instálala:

   ```sh
   prover/scripts/download-face-models.sh
   prover/scripts/build-android-lib.sh
   cd android && JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :app:assembleDebug
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

2. Levanta el verificador sólo en el computador y abre un túnel por USB:

   ```sh
   cd prover && cargo run --release -p verifier-web -- --host 127.0.0.1
   adb reverse tcp:8443 tcp:8443
   ```

   Uso `127.0.0.1` porque en una red con IPs públicas el servidor quedaría expuesto. Si el
   teléfono muestra `ConnectException: Failed to connect to /127.0.0.1:8443`, es que el túnel
   se cayó: `adb reverse --list` sale vacío, y se arregla repitiendo `adb reverse`.
   El servidor acepta además `--trust-mock`, para la CSCA de prueba, y `--allow-no-chip`, para
   pruebas sin Active Authentication.
3. En la app, escribe el número de documento y las fechas de nacimiento y de vencimiento
   (`AAMMDD`), toca "Leer cédula" y acerca la cédula. En el resultado, toca "Comparar mi
   cara" y, cuando coincida, "Generar prueba".
4. Abre https://127.0.0.1:8443 en el computador, acepta el certificado autofirmado y escanea
   el QR con "Presentar mi prueba".

Los tests instrumentados de Android borran claves y desinstalan la app. Córrelos sólo en un
emulador (`ANDROID_SERIAL=emulator-5554`).

## Mapa del repositorio

| Carpeta | Qué hay |
| --- | --- |
| [`prover/core`](prover/core) | Las comprobaciones de la cédula, en Rust `no_std`. Las usan el programa dentro de la prueba y los tests |
| [`prover/methods`](prover/methods) | El programa (guest) de RISC Zero y su compilación |
| [`prover/host`](prover/host) | Probador de línea de comandos, para el computador y las mediciones por `adb` |
| [`prover/ffi`](prover/ffi) | El probador como librería JNI para la app |
| [`prover/verifier`](prover/verifier), [`prover/verifier-web`](prover/verifier-web) | La librería que verifica presentaciones y el servidor web con QR |
| [`prover/facematch`](prover/facematch) | Comparación de cara y prueba de vida, adaptadas del código de ZKPassport |
| [`prover/fixtures`](prover/fixtures) | Generador de la cédula de prueba. Las claves son desechables |
| [`android/app`](android/app) | La app: lectura NFC con JMRTD, comparación de cara, clave del teléfono, servicio del probador, presentación |
| [`android/verifier`](android/verifier) | Verificación en Kotlin de la cédula, de Active Authentication y de ML-DSA |

## Documentación

| Documento | Tipo |
| --- | --- |
| [`docs/plan.md`](docs/plan.md) | Plan por etapas y su estado |
| [`docs/decisions/`](docs/decisions) | Decisiones, de la 0001 a la 0007 |
| [`docs/measurements/`](docs/measurements) | Mediciones, con fecha y condiciones |

## Licencia

[Apache-2.0](LICENSE). Las dependencias tienen su propia licencia; JMRTD y SCUBA son LGPL.
YuNet es MIT y SFace Apache-2.0; el script de descarga guarda sus licencias junto a los
modelos.
