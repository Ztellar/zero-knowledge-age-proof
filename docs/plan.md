# Plan: prueba de edad post-cuántica con la cédula chilena

Fecha: 2026-10-22.

Estado: **etapa 3 hecha** (2026-10-25): el probador corre en el computador y en el S25 con
una cédula de prueba; resultados en `measurements/stage-1-desktop.md` y
`measurements/stage-2-android.md`. La app lee la cédula real y verifica sus firmas en Kotlin; todavía no genera la prueba.

## Objetivo

Proyecto de portfolio, open source y educativo
([decisión 0003](decisions/0003-android-app-post-quantum-proof.md)). Primera versión: una
app Android que lee la cédula chilena por NFC, comprueba la firma del Registro Civil y
genera en el teléfono una prueba STARK de "mayor de 18", sin que ningún dato salga del
teléfono.

## Qué es post-cuántico y qué no

| Capa | Algoritmo | ¿Post-cuántico? |
| --- | --- | --- |
| Firma del Registro Civil (CSCA → DSC → SOD) | RSA 3072/4096 con SHA-256 | **No, y no depende de nosotros.** Se documenta como límite |
| Prueba de conocimiento cero | STARK (sólo funciones hash) | **Sí** (se considera resistente). Es la pieza central |
| Firma de la clave del dispositivo (versión futura) | ML-DSA, FIPS 204 | Sí, si el teléfono lo soporta; no verificado |
| OPRF del registro (en pausa) | Curvas elípticas | No: las versiones post-cuánticas son investigación |

## Qué prueba la prueba

Entradas privadas: DG1 (los datos del MRZ), el SOD (los hashes firmados y su firma) y el
certificado DSC. Entradas públicas: la fecha del día D y la clave de una CSCA chilena.
Salida: "mayor de 18 en D".

El circuito comprueba:

1. el DSC está firmado por la CSCA (RSA);
2. el SOD está firmado por el DSC (RSA);
3. el hash de DG1 coincide con el que trae el SOD;
4. con la fecha de nacimiento de DG1, la persona tiene 18 años o más en D;
5. el documento no está vencido en D.

Trampas conocidas:
- **El año de nacimiento viene con dos dígitos** (`YYMMDD`): el siglo se resuelve contra D.
- **D es entrada pública**, y quien verifica rechaza fechas viejas.
- **Esquema de firma sin confirmar:** RSA PKCS#1 v1.5 o PSS. Se ve al leer una cédula real
  (etapa 3).

## Stack

- **App:** Kotlin nativo. Lectura NFC con [JMRTD](https://jmrtd.org) (Java, maduro,
  soporta BAC y PACE).
- **Núcleo criptográfico:** Rust, llamado desde Kotlin con UniFFI.
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
   que trae sólo módulo y exponente: el core en Rust hoy espera el certificado completo de
   la CSCA y hay que adaptarlo en la etapa 4.
4. **Integración.** La app lee la cédula y genera la prueba en el teléfono, una sola vez
   y en segundo plano. La prueba incluye en su journal una clave pública ML-DSA del
   teléfono ([decisión 0004](decisions/0004-prove-once-present-many.md)).
   *Salida:* prueba generada a partir de una cédula real, sin red, y guardada en la app.
   Antes de partir: la cédula de prueba ahora usa los tamaños de clave reales (CSCA RSA
   3072, DSC RSA 2048); en el S25 la prueba sucinta tarda 58–65 s con 2,46 GB
   ([tamaños de clave y medición](measurements/chile-key-sizes.md)).
5. **Presentación, verificador y documentación.** El verificador manda un desafío y el
   teléfono responde con la prueba guardada y una firma ML-DSA. Verificador (CLI o web) y
   README con el mapa post-cuántico, las mediciones, la limitación de enlazabilidad de la
   0004 y un artículo corto.

## Riesgos

| Riesgo | Qué pasa si se cumple |
| --- | --- |
| RISC Zero no compila o no corre en Android | Descartado en la etapa 2: corre en el S25 |
| Generar la prueba tarda minutos en el teléfono | Tarda 65 s en el S25; un teléfono de gama media puede tardar más o quedarse sin memoria (sin medir) |
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
La app móvil no es código abierto todavía: lo que hace se infiere de la documentación y del
SDK.

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
