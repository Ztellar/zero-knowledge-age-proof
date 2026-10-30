# Tamaños de clave de las cédulas chilenas

Fecha: 2026-10-26. Para qué: que la cédula de prueba use los mismos tamaños de clave que
las cédulas reales, y saber qué tamaños tiene que soportar el circuito.

## Fuentes y lo que dicen

| Fuente | Qué trae | Chile |
| --- | --- | --- |
| Registro de ZKPassport, `certificates.json` ([URL](https://raw.githubusercontent.com/zkpassport/zkpassport-packages/HEAD/packages/registry-explorer/public/assets/certificates/certificates.json), bajado el 2026-10-22, sha256 `fc5ce6fe…f321e2`) | CSCA: módulo, exponente, vigencia | 4 CSCA RSA con SHA-256 y exponente 65537: 2013 (3072), 2016 (3072), 2021 (3072), 2024 (4096) |
| [Reglas de soporte de ZKPassport](https://github.com/zkpassport/zkpassport-packages/blob/fac2812/packages/registry-sdk/src/document-support-rules.json) (commit `fac2812`) | Nivel de soporte por país | Cédula `id_card_support: 0` (`NOT_SUPPORTED`), pasaporte `1`. Aun así una cédula real funcionó en su app |
| [Mapa de CSCA de Self](https://raw.githubusercontent.com/selfxyz/self/52dba2742b4c37a957eb5ab8ebee83fdccdcf187/registry/outputs/map_csca.json) (commit `52dba274`, 2024-12-01), sacado del ICAO PKD | Cantidad de CSCA por algoritmo y tamaño | 3 CSCA RSA 3072 con SHA-256 (la de 2024 todavía no aparecía) |
| [Mapa de DSC de Self](https://raw.githubusercontent.com/selfxyz/self/52dba2742b4c37a957eb5ab8ebee83fdccdcf187/registry/outputs/map_dsc.json) (mismo commit) | Cantidad de DSC por algoritmo y tamaño | **Chile no aparece:** no publica sus DSC en el ICAO PKD |
| Registro Civil y Diario Oficial | Normas de la cédula | Dicen que sigue ICAO Doc 9303; no publican tamaños de clave |
| Una cédula real, leída con la app el 2026-10-25 | El DSC dentro del SOD | DSC RSA 2048, firmado por la CSCA 2021 (RSA 3072); SOD con SHA-256 y RSA PKCS#1 v1.5; acceso PACE |

## Conclusión

- **Las CSCA están confirmadas** por dos fuentes independientes: RSA 3072 hasta 2021 y
  RSA 4096 desde 2024.
- **El tamaño del DSC sólo está confirmado para una cédula:** RSA 2048 bajo la CSCA 2021.
  Como Chile no publica sus DSC, la única forma de conocer otros es leer más cédulas. **No
  se sabe** qué tamaño usan los DSC bajo la CSCA 2024, es decir, las cédulas del formato
  nuevo emitidas desde diciembre de 2024.
- **El circuito tiene que aceptar RSA de 2048 a 4096 bits** en las dos firmas. El crate
  `rsa` ya lo hace; las pruebas cubren 3072/2048 (por omisión) y 4096/4096 (peor caso).

## Comparación con la lista maestra de la OACI (2026-10-29)

Fuente: `ICAO_ML_20261013112327.ml`, descargada por el usuario desde icao.int (sha256
`fcb9cce9…5ab31f`). La firma CMS verifica (`openssl cms -verify`) con el "ICAO Master List
Signer", y ese firmante encadena al "United Nations CSCA" (sha256 `920693CD…D92E4E`). Ese
ancla se tomó del mismo archivo y no se contrastó con otra fuente.

Chile tiene 9 certificados en la lista: 5 claves autofirmadas y 4 certificados de enlace,
que forman una cadena continua 2013 → 2016 → 2021 → 2024 → 2026.

| Clave | En la lista de la OACI | En `ChileCsca.kt` |
| --- | --- | --- |
| 2013, RSA 3072 | Sí | Sí, mismo módulo |
| 2016, RSA 3072 | Sí | Sí, mismo módulo |
| 2021, RSA 3072 | Sí | Sí, mismo módulo |
| 2024, RSA 4096 | Sí | Sí, mismo módulo |
| **2026, RSA 4096** (emitida el 2026-05-05; firma documentos del 2026-05-05 al 2031-05-05) | Sí | **No** |

- Las 4 claves de la app son las oficiales.
- **Falta la de 2026.** Tampoco está en el registro de ZKPassport: su `certificates.json`
  sigue con el mismo sha256 `fc5ce6fe…`.
- Un documento cuyo DSC haya firmado la clave 2026 se rechazaría como "CSCA no reconocida".
  Falla de forma segura, pero falla. **No se sabe** qué documentos usa esa clave (cédulas,
  pasaportes o ambos).
- No se copió la clave al repo: los términos de la lista maestra prohíben republicarla sin
  permiso escrito de la OACI.

## Medición con los tamaños reales

Cédula de prueba regenerada con `gen.py` por omisión (CSCA 3072, DSC 2048). Mac M1 Pro,
mismo método que la [etapa 1](stage-1-desktop.md), una corrida por fila.

| Paso | 4096 / 4096 | 3072 / 2048 |
| --- | --- | --- |
| Firma del DSC (clave de la CSCA) | 77.050 | 65.335 |
| Firma del SOD (clave del DSC) | 74.981 | 53.093 |
| Interpretar SOD y certificados | 21.080 | 15.704 |
| **Total del programa** | **198.377** | **159.368** |

| Recibo | Ciclos totales | Tiempo de prueba | RAM máxima | Tamaño |
| --- | --- | --- | --- | --- |
| Sucinto | 262.144 | 32,7 s | 2,16 GB | 223.514 B |
| Compuesto | 262.144 | 26,2 s | 1,75 GB | 256.250 B |

- **El tiempo no baja:** 159.368 más 47.344 de paginación suman 206.712, más que
  2^17 = 131.072, así que la prueba sigue en un segmento de 2^18.
- **Antes de medir estimé unos 108.000 ciclos**, suponiendo que RSA cuesta el cuadrado del
  tamaño de la clave. Fue un error: la firma de 2048 bits cuesta el 71% de la de 4096
  (53.093 / 74.981), no el 25%.
- **En el Mac la RAM baja:** 2,57 → 2,16 GB en la sucinta. La compuesta dio 26,2 s contra 22,8 s
  antes; con una sola corrida por fila, esa diferencia puede ser ruido.

### En el S25

Mismo método que la [etapa 2](stage-2-android.md). `MemAvailable` antes de partir:
2.941.132 kB. RAM en GB = `VmHWM` × 1.024.

| Recibo | Corrida | Tiempo de prueba | RAM máxima | Verificación |
| --- | --- | --- | --- | --- |
| Sucinto | 1 | 58,58 s | sin dato válido | 14,4 ms |
| Sucinto | 2 | 64,89 s | sin dato válido | 18,1 ms |
| Sucinto | 3 | 57,67 s | 2,46 GB (2.406.864 kB) | — |
| Compuesto | 1 | 43,09 s | sin dato válido | 22,5 ms |
| Compuesto | 2 | 41,19 s | 2,46 GB (2.405.360 kB) | — |

- **"Sin dato válido":** en esas corridas el script medía la RAM de un subshell (1,3–1,4
  MB) en vez de la del probador. Se corrigió antes de las corridas con RAM. Las mediciones
  de la etapa 2 usaron otro script, que lanzaba el probador directo, y no tienen este
  problema.
- **Tiempo:** la sucinta va de 57,7 a 64,9 s, contra 64,7–65,0 s con 4096/4096. La
  diferencia es chica y del mismo orden que la variación entre corridas.
- **RAM:** en el teléfono no baja (2,46 GB contra 2,34–2,47 GB), aunque en el Mac bajó.
  Lo que domina es el tamaño del segmento (2^18), no el de las claves.
