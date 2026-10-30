# Prueba de edad

[![Android: funciona](https://img.shields.io/badge/Android-funciona-1F7A4D?logo=android&logoColor=white)](#en-qué-estado-está)
[![iOS: no disponible](https://img.shields.io/badge/iOS-no%20disponible-8C9BBA?logo=apple&logoColor=white)](#en-qué-estado-está)
[![SDK para empresas: propuesta](https://img.shields.io/badge/SDK%20para%20empresas-propuesta-8C9BBA)](#un-sdk-para-empresas-propuesta)
[![Prueba: STARK post-cuántica](https://img.shields.io/badge/prueba-STARK%20post--cu%C3%A1ntica-1846C4)](docs/technical.md#qué-es-post-cuántico-y-qué-no)
[![Licencia: Apache-2.0](https://img.shields.io/badge/licencia-Apache--2.0-1846C4)](LICENSE)

**Demuestra que eres mayor de edad sin mostrar tu cédula.**

Hoy, para probar que tienes 18 años, entregas tu cédula y quien la mira ve tu nombre, tu
RUN, tu fecha de nacimiento y tu foto. Esta app hace lo contrario: lee el chip de tu
cédula chilena con el teléfono y genera una prueba matemática de que eres mayor de 18. Quien
la recibe sólo sabe eso. No ve tu nombre, tu RUN, tu fecha de nacimiento ni tu foto.

## Cómo se usa

La primera vez, en tres pasos y unos minutos:

1. **Lees tu cédula con el teléfono.** Muestras el reverso a la cámara (o escribes tres
   datos) y apoyas la cédula en el teléfono para leer su chip.
2. **Confirmas que eres tú.** La app compara tu cara con la foto del chip, con una prueba de
   vida. Todo ocurre en el teléfono.
3. **El teléfono genera tu prueba.** Tarda entre uno y tres minutos y puedes salir de la app
   mientras tanto.

Después, cada vez que te pidan la edad, tocas **Mostrar mi prueba** y escaneas el código QR
que te muestran. El sitio recibe sólo un "sí, es mayor de 18".

Nada de tu cédula sale del teléfono: ni los datos, ni la foto, ni las imágenes de la cámara.

## Dónde se aplicaría

En cualquier lugar donde hoy se pide la cédula sólo para ver la edad:

- **Comercio en línea** de productos con restricción de edad, como alcohol o tabaco.
- **Entradas a eventos y locales**: en la puerta, la persona escanea el QR del verificador en
  vez de entregar la cédula.
- **Sitios y apps con contenido para adultos**, sin pedir una copia de la cédula ni guardar
  datos personales.
- **Cualquier servicio que deba comprobar la edad** y prefiera no recibir datos que después
  tiene que proteger.

Para quien verifica, la ventaja es no guardar datos personales que proteger. La prueba dice
también que el documento lo firmó el Registro Civil, que está vigente y que el chip original
estaba junto al teléfono.

## Qué usa

| Pieza | Qué hace | Con qué |
| --- | --- | --- |
| Chip de la cédula | Trae los datos firmados por el Registro Civil | NFC, estándar ICAO 9303, [JMRTD](https://jmrtd.org) |
| Lectura del reverso | Llena los datos de acceso al chip sin escribirlos | ML Kit de Google, en el teléfono |
| Comparación de cara | Comprueba que quien usa la app es la persona de la foto | YuNet y SFace (OpenCV Zoo), en el teléfono |
| Prueba de conocimiento cero | Demuestra "mayor de 18" sin revelar nada más | STARK de [RISC Zero](https://github.com/risc0/risc0), que sólo usa funciones hash |
| Firma del teléfono | Liga la prueba a tu teléfono y a cada sitio | ML-DSA-65 (FIPS 204) |

**Por qué post-cuántica:** la prueba y la firma del teléfono resisten un computador
cuántico. Otros proyectos parecidos, como [ZKPassport](https://github.com/zkpassport) o la
[librería experimental de la Comisión Europea](https://github.com/eu-digital-identity-wallet/av-lib-android-zkp-age-icao),
usan criptografía de curvas elípticas, que no la resiste. Hay un límite: las firmas del Registro Civil son RSA y las elige el Estado
([detalle](docs/technical.md#qué-es-post-cuántico-y-qué-no)).

## En qué estado está

| Parte | Estado |
| --- | --- |
| App Android | **Funciona.** Probada con una cédula real en un Galaxy S25: leyó el chip, comparó la cara, generó la prueba en 92,5 s y el verificador la aceptó. Sólo se ha probado en ese teléfono |
| Interfaz accesible (modo oscuro, letra hasta 200%, etiquetas para lector de pantalla) | Implementada el 2026-10-29. Recorrida en el S25 con la cédula real hasta generar la prueba (2026-10-30). Falta probar la lectura del reverso con una cédula real, presentar la prueba nueva y usarla con TalkBack |
| Verificador web | **De referencia.** Funciona en una red local, para demostraciones |
| iOS | **No disponible.** No hay código para iPhone |
| SDK para empresas | **Propuesta, no implementada** ([abajo](#un-sdk-para-empresas-propuesta)) |
| Play Store | No publicada |
| Auditoría de seguridad | No hecha |

Es un proyecto open source y educativo, no un producto
([decisión 0003](docs/decisions/0003-android-app-post-quantum-proof.md)). Si puede pasar a uso
real está en discusión en la [decisión 0008](docs/decisions/0008-real-use-and-whether-to-continue.md).

## Un SDK para empresas (propuesta)

> **No implementado.** Lo que sigue es una propuesta. Hoy existen el verificador web de
> referencia ([`prover/verifier-web`](prover/verifier-web)) y la librería en Rust que verifica
> presentaciones ([`prover/verifier`](prover/verifier)).

La idea es que un comercio no tenga que entender la criptografía para aceptar la prueba.
Bastarían dos piezas:

1. **Un componente para su página o app** que muestra el QR y espera la respuesta del
   teléfono, como hace hoy el verificador web.
2. **Una librería para su servidor** que comprueba la prueba y devuelve sólo "mayor de 18: sí
   o no", para los lenguajes más usados.

Cada verificador podría diseñar su propia pantalla. Lo que no cambia es la pantalla de la
app que pregunta "¿Mostrar tu prueba a este sitio?". Ahí la persona ve qué sitio pide la
prueba y qué va a saber.

## Para desarrolladores

Cómo funciona por dentro, lo medido, los límites conocidos y cómo compilar y probar están en
la [documentación técnica](docs/technical.md). Las decisiones están en
[`docs/decisions/`](docs/decisions) y las mediciones en [`docs/measurements/`](docs/measurements).

## Licencia

[Apache-2.0](LICENSE). Las dependencias tienen su propia licencia: JMRTD y SCUBA son LGPL,
YuNet es MIT y SFace Apache-2.0. La letra Atkinson Hyperlegible Next es SIL OFL 1.1; su
licencia va en [`android/app/src/main/assets/licenses`](android/app/src/main/assets/licenses).
