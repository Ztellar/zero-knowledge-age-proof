# 0007 — Comparación de cara

Fecha: 2026-10-29. Estado: **aceptada: B, partiendo del código de ZKPassport (E)**.
Implementada el 2026-10-29 y probada con la cédula real en el S25: la cara coincidió, pasó la
prueba de vida y después se generó la prueba. Sigue a la
[0006](0006-active-authentication-in-proof.md).

## Contexto

Con la 0006, la prueba demuestra que el chip de la cédula estaba junto al teléfono, pero no
que lo usó su dueño: quien tenga la cédula de otra persona en la mano puede generar su
prueba. ZKPassport compara la cara de quien usa la app con la foto del chip, con prueba de
vida y atestación del dispositivo ([plan](../plan.md#lo-que-zkpassport-ya-resuelve)).

La foto está en DG2: ICAO 9303 la guarda ahí, normalmente en JPEG 2000. Su hash va en el
SOD, así que la foto es tan auténtica como DG1.

La cédula real usada en las pruebas trae DG2, y la app la muestra como `image/jp2` (JPEG
2000).

### Lo que hay que resolver

1. **¿Quién le cree al resultado?** La cara de quien usa la app la toma la cámara, y ni el
   Registro Civil ni el chip firman esa imagen. Nada impide que una app modificada le pase al
   comparador la propia foto de DG2 como si fuera la cámara.
2. **¿Dónde corre?** En el teléfono, sin red, como todo lo demás.
3. **¿Con qué modelo?** Tiene que ser de código y pesos abiertos, con una licencia que
   permita publicarlo.

## Opciones

### A. Dentro de la prueba

El programa de RISC Zero leería DG2, comprobaría su hash contra el SOD y correría la red de
reconocimiento sobre la foto del chip y sobre la de la cámara.

- **No agrega seguridad.** La foto de la cámara es una entrada privada que nadie firma, así
  que una app modificada pasa DG2 dos veces y el resultado es siempre "coincide".
- **Es inviable de costo.** MobileFaceNet hace unos 221 millones de multiplicaciones y sumas
  por imagen (Chen et al., 2018). Suponiendo 10 ciclos por operación en el zkVM, que no
  tiene unidad de punto flotante (supuesto sin medir), son 2 × 221 × 10⁶ × 10 ≈ 4,4 × 10⁹
  ciclos. Eso es 4,4 × 10⁹ / 379.900 ≈ 11.600 veces el programa actual, que ya tarda 92,5 s
  en el S25: más de un día. A eso se suma decodificar JPEG 2000.

### B. En la app, fuera de la prueba, como ayuda al usuario honesto

La app compara la cámara con DG2 antes de generar la prueba y se niega a generarla si no
coinciden. El resultado no entra en el `Claim` y el verificador no recibe nada nuevo.

- **Protege del caso casual:** alguien que toma la cédula de otra persona y usa la app
  publicada. No protege de quien compile la app sin esa comprobación, porque el código es
  abierto.
- **No le promete nada al verificador**, y así se documenta. Dice lo mismo que hoy.
- **Cuesta:** leer DG2, decodificar JPEG 2000 en Android, un detector de caras y un modelo
  de reconocimiento dentro de la APK, que ya pesa 137 MB.

### C. En la app, con atestación del dispositivo

Como B, pero el resultado se firma con una clave que Android Key Attestation o Play Integrity
asocian a la app oficial, sin modificar. El verificador comprueba esa atestación.

- **Es lo único que le da al verificador una garantía sobre la cara**, y es lo que hace
  ZKPassport.
- **Contradice el proyecto.** Quien compile el código abierto ya no tiene una app válida, y
  la confianza pasa a Google y a la firma de quien publica la app. Además, la atestación usa
  ECDSA o RSA, que no son post-cuánticos.
- **Necesita prueba de vida**, porque si no basta mostrarle a la cámara una foto de la
  cédula. Una prueba de vida seria, resistente a máscaras y a videos, es un producto en sí
  mismo.

### D. No comparar la cara

Se deja documentado como límite: la prueba demuestra que el chip estaba presente, no que lo
usó su dueño.

### E. Reusar la implementación de ZKPassport

ZKPassport publicó su app en [mobile-app](https://github.com/zkpassport/mobile-app)
(Apache-2.0, commit `c52f5ef`, un solo push el 2026-07-17, así que puede no ser la versión que
está en las tiendas). Lo revisado:

- **El código es reusable.** El módulo
  [`modules/facematch/rust`](https://github.com/zkpassport/mobile-app/tree/main/modules/facematch/rust)
  es Rust con [`ort`](https://crates.io/crates/ort) (ONNX Runtime) y hace la detección
  (entrada de 640 × 640), la alineación por 5 puntos a 112 × 112 y el embedding. También
  calcula la pose de la cabeza en `pose.rs`. La prueba de vida pide girar la cabeza en las
  direcciones que muestra la pantalla (`LIVENESS_SCHEDULE` en `FaceMatch.tsx`). Su licencia es
  compatible con la de este proyecto, siempre que se conserve la atribución.
- **Los modelos no vienen en el repo.** [su script de descarga](https://github.com/zkpassport/mobile-app/blob/main/scripts/download-facematch-models.sh) los baja de
  `cdn.zkpassport.id`: `scrfd_2.5g_bnkps.ort` y `arcface.ort`. SCRFD 2.5G es un detector de
  InsightFace, cuyos modelos son sólo para investigación no comercial. El origen de
  `arcface.ort` no está declarado; por el nombre y el formato es probable que también sea de
  InsightFace (sin verificar). Usarlos aquí obligaría a bajarlos aparte con esa restricción,
  igual que hace ZKPassport, y no se podrían publicar bajo Apache-2.0.
- **La garantía para el verificador la da la atestación**, no la comparación:
  [`modules/app-attest-module`](https://github.com/zkpassport/mobile-app/tree/main/modules/app-attest-module)
  usa Play Integrity en Android y App Attest en iOS. Sin eso, reusar su código deja a este
  proyecto en la opción B.

Así que E es una forma de hacer B, no una opción aparte: su código con YuNet y SFace en vez de
los modelos de InsightFace. Hay que revisar si el preprocesamiento coincide, porque SFace
también usa caras alineadas de 112 × 112, pero puede normalizar distinto (sin verificar).

## Modelos abiertos revisados (2026-10-29)

Las licencias se leyeron en la página del modelo; no se revisaron los términos de los datos
de entrenamiento.

| Modelo | Para qué | Licencia de los pesos | Nota |
| --- | --- | --- | --- |
| [YuNet](https://huggingface.co/opencv/face_detection_yunet) (OpenCV Zoo) | Detectar la cara | MIT | — |
| [SFace](https://huggingface.co/opencv/face_recognition_sface) (OpenCV Zoo) | Reconocimiento, una variante de MobileFaceNet | Apache-2.0 | Su README da 0,9940 en su evaluación, sin decir la métrica |
| [AuraFace v1](https://huggingface.co/fal/AuraFace-v1) | Reconocimiento, ResNet100 tipo ArcFace | Apache-2.0 | LFW 0,9965. Pesado para un teléfono; tamaño no publicado |
| [InsightFace](https://pypi.org/project/insightface/) (`buffalo_l`) | Detección y reconocimiento | **Sólo investigación no comercial** | Se descarta: no se puede publicar con el proyecto |

## Ley 21.719

Los rasgos faciales que "permitan o confirmen la identificación única" de una persona son
datos biométricos (Art. 16 ter), y los datos biométricos son datos sensibles (Art. 2 letra g).
Para tratarlos, el responsable debe informar el sistema biométrico, la finalidad, el período
de uso y cómo ejercer los derechos (Art. 16 ter).

En B y C la comparación ocurre en el teléfono y nada sale de él. Si eso deja al proyecto sin
un "responsable" del tratamiento, o lo cubre la excepción de actividades personales
(Art. 1), es una interpretación **sin verificar**. En cualquier caso, la app tendría que
mostrar esa información antes de usar la cámara.

Fuente: texto oficial de la Ley 21.719, en las fuentes de la skill `compliance-cl`.

## Lo implementado

Verificado el 2026-10-29.

- **Núcleo:** [`prover/facematch`](../../prover/facematch/src/lib.rs), en Rust con
  [`tract`](https://crates.io/crates/tract-onnx), que corre ONNX sin binarios nativos. Hace
  la detección con YuNet, la alineación por 5 puntos, el embedding con SFace y la pose.
  Decodifica la foto del chip en JPEG o JPEG 2000 con `jpeg2k` (port en Rust de OpenJPEG).
  Los modelos los baja [`download-face-models.sh`](../../prover/scripts/download-face-models.sh),
  fijados por revisión y sha256, junto con sus licencias.
- **En la app:**
  - comprueba que DG2 coincida con su hash en el SOD;
  - muestra el aviso del Art. 16 ter antes de pedir la cámara;
  - compara cada cuadro de la cámara frontal en
    [`FaceCheck.kt`](../../android/app/src/main/kotlin/cl/zkage/app/FaceCheck.kt);
  - no deja generar la prueba hasta que la cara coincide.

  Una cédula sin foto válida en DG2 no sigue (cambiado el 2026-10-29: antes generaba la
  prueba sin comparar la cara; ver `Gate.kt`). Nada de esto entra en la prueba.
- **Prueba de vida:** mirar de frente, girar la cabeza a la derecha, después a la izquierda, y
  volver a mirar de frente. El orden es fijo desde el 2026-10-30: el usuario lo probó en el
  S25 y la pantalla mostraba una secuencia que la app no exigía. Girar al lado equivocado no
  cuenta. Los cuadros de frente exigen la similitud completa (0,363); los girados, 0,15. Si la
  cara se pierde por más de 10 cuadros, los giros se reinician. Detiene una foto; no detiene
  un video ni una máscara. Al terminar, la pantalla muestra "¡Bien!" con los cuatro pasos marcados
  durante 0,9 s y después "Comprobando que eres tú…" durante 2 s antes de generar la prueba:
  es sólo para que la persona vea el resultado, no agrega ninguna comprobación.

Diferencias con ZKPassport, cada una por algo medido en el S25:

| ZKPassport | Aquí | Por qué |
| --- | --- | --- |
| Arriba, abajo, izquierda y derecha | Sólo izquierda y derecha | Mirando hacia arriba, la similitud con la foto frontal bajó a 0,18–0,28 y la prueba casi nunca terminaba. Arriba y abajo no agregan nada contra una foto |
| Giro medido desde cero | Desde la pose de frente de cada persona | Una cara de frente no tiene pitch cero (−0,18 en los retratos de prueba) |
| Umbral de giro de 0,23 | 0,15 de yaw | Con los puntos de YuNet, un giro claro llegó a 0,17–0,26 |
| Pitch dividido por la distancia entre los ojos | Dividido por la altura de ojos a boca | Al girar, la distancia entre los ojos se achica y un giro se leía como levantar la cabeza |

Medido en el S25:

| Qué | Resultado |
| --- | --- |
| Tiempo por cuadro de 640 × 480 (detección y embedding) | Mediana de 65 a 96 ms en cinco sesiones (75, 95, 67, 96 y 65 ms) |
| Prueba de vida completa con la cédula real | 65 cuadros |
| Sesiones registradas antes del ajuste, reproducidas con la lógica final | Las cuatro se completan, entre los cuadros 57 y 138 |

En el Mac, con los retratos de dominio público: 0,688 entre dos fotos de la misma persona,
0,590 y 0,658 con una de ellas reducida al tamaño de una foto de chip (en gris y en JPEG
2000), y 0,10 y 0,15 entre personas distintas.

**Costo:** la APK de debug pasó de 137 a 200 MB. SFace pesa 38,7 MB y la librería creció
16,7 MB por `tract` y el decodificador.

**Sin medir:** la tasa de falsos rechazos y de falsos aceptados. Se probó con una persona y
tres retratos públicos.

## Recomendación

**D por ahora, y B si se quiere mostrar en la demo**, nunca como garantía para el
verificador.

- A queda descartada: no agrega seguridad y su costo no cabe en un teléfono.
- C le da garantía al verificador, pero a cambio de lo que hace distinto al proyecto: código
  abierto que cualquiera puede compilar, y post-cuántico en lo que depende de él.
- B sirve para el portfolio, con detección y reconocimiento en el teléfono, siempre que la
  app y el README digan que es una ayuda local, no una prueba. Conviene hacerla partiendo del
  módulo Rust de ZKPassport (E), con modelos de licencia permisiva.

Decisión (2026-10-29): B, partiendo del código de ZKPassport (E), con YuNet y SFace. Lo que
se pedía antes de implementarla:

1. Confirmar que la cédula real trae DG2: sí, en JPEG 2000.
2. Medir el peso que agregan a la APK el modelo y el decodificador de JPEG 2000: 63 MB, de 137
   a 200 MB.
3. Usar sólo fotos con licencia en los tests, nunca la foto de la cédula real: los tests usan
   retratos de dominio público
   ([`tests/faces`](../../prover/facematch/tests/faces/README.md)).
