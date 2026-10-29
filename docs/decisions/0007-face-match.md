# 0007 — Comparación de cara

Fecha: 2026-10-29. Estado: **propuesta**, sin implementar. Sigue a la
[0006](0006-active-authentication-in-proof.md).

## Contexto

Con la 0006, la prueba demuestra que el chip de la cédula estaba junto al teléfono, pero no
que lo usó su dueño: quien tenga la cédula de otra persona en la mano puede generar su
prueba. ZKPassport compara la cara de quien usa la app con la foto del chip, con prueba de
vida y atestación del dispositivo ([plan](../plan.md#lo-que-zkpassport-ya-resuelve)).

La foto está en DG2: ICAO 9303 la guarda ahí, normalmente en JPEG 2000. Su hash va en el
SOD, así que la foto es tan auténtica como DG1.

**Sin verificar:** que la cédula chilena traiga DG2, y en qué formato.

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

## Recomendación

**D por ahora, y B si se quiere mostrar en la demo**, nunca como garantía para el
verificador.

- A queda descartada: no agrega seguridad y su costo no cabe en un teléfono.
- C le da garantía al verificador, pero a cambio de lo que hace distinto al proyecto: código
  abierto que cualquiera puede compilar, y post-cuántico en lo que depende de él.
- B sirve para el portfolio, con detección y reconocimiento en el teléfono, siempre que la
  app y el README digan que es una ayuda local, no una prueba. Conviene hacerla partiendo del
  módulo Rust de ZKPassport (E), con modelos de licencia permisiva.

Antes de implementar B:

1. Confirmar que la cédula real trae DG2. La pantalla de resultado de la lectura ya muestra
   "Data groups en el SOD", que son sólo números y no exponen datos personales.
2. Medir el peso que agregan a la APK el modelo y el decodificador de JPEG 2000.
3. Usar sólo cédulas de prueba en los tests, con fotos sintéticas o de bancos con licencia,
   nunca la foto de la cédula real.
