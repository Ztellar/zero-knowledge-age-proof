# 0008 — Uso real y si vale la pena seguir

Fecha: 2026-10-29. Estado: **propuesta, esperando la elección del usuario**. Reabre en
parte la [0003](0003-android-app-post-quantum-proof.md).

## Contexto

La 0003 dejó el proyecto como portfolio porque "como negocio no compite" con ZKPassport.
Desde entonces cambiaron dos cosas:

- **La primera versión funciona.** Lee la cédula real, prueba con Active Authentication en
  92,5 s en el S25, y el verificador web la aceptó. La 0003 decía que el resto "se retoma
  si la primera versión funciona".
- **El usuario pregunta si sirve para uso real.** El rediseño accesible del 2026-10-29
  apunta a público general.

Hay que responder dos preguntas: si esto puede servir de verdad, y si vale la pena seguir.

## ¿Puede servir de verdad?

**Técnicamente, sí.** La cadena completa ya funciona con una cédula real: lectura, firma del
Registro Civil, chip original, cara, prueba y verificador. Lo que falta para que una
persona cualquiera lo use no es criptografía:

| Falta | Por qué importa | Cómo se sabría |
| --- | --- | --- |
| **Alguien que verifique** | Sin un comercio o sitio que acepte la prueba, la app no le sirve a nadie | Un verificador concreto dispuesto a probarla |
| **Teléfonos comunes** | `SegmentSize` usa el 40% de la RAM. Con 6 GB (2,4 GB de presupuesto) cae en 2^17: 366 s medidos en el S25. En una CPU más lenta sería más; no está medido | Medirlo en un teléfono de gama media |
| **iPhone** | Hoy sólo hay Android | — |
| **Claves del Estado de fuente oficial** | Las CSCA vienen del registro de ZKPassport (`ChileCsca.kt`). Chile es participante del ICAO PKD desde el 19/07/2024, y el PKD se descarga sin cuenta aceptando términos que **prohíben republicar** su contenido sin permiso escrito de la OACI (revisado el 2026-10-29). No se encontró una publicación del Registro Civil. Comparadas con la lista maestra de la OACI, las 4 claves de la app son las oficiales, pero **falta una quinta, de 2026** ([detalle](../measurements/chile-key-sizes.md)) | Pedir permiso a la OACI o pedir las CSCA al Registro Civil por Ley de Transparencia |
| **Revisión de seguridad independiente** | Nadie fuera del proyecto ha revisado el programa de la prueba | — |
| **Enlazabilidad** | Dos verificadores pueden saber que es la misma prueba ([0004](0004-prove-once-present-many.md)) | — |

ZKPassport, Self y la librería experimental de la Comisión Europea
(`av-lib-android-zkp-age-icao`) ya hacen esto con curvas elípticas. Lo que este proyecto
tiene distinto es la prueba basada sólo en funciones hash. Eso importa cuando los Estados
firmen documentos con algoritmos post-cuánticos, y la OACI recién está trabajando en ello.

## Opciones

### A. Terminar y cerrar

Terminar el rediseño, publicar el resultado y un artículo, y dejar el proyecto como
portfolio.

- Cuesta: lo ya aprobado.
- Se rompe cuando: un verificador real o una institución pide usarlo.

### B. Pieza abierta para otros (recomendada)

Terminar el rediseño y convertir lo distintivo en algo que otros puedan reusar:

- una especificación del protocolo de verificación, con vectores de prueba;
- la prueba post-cuántica como librería;
- una demostración de punta a punta con una cédula de prueba firmada con ML-DSA.

- Cuesta: semanas de trabajo propio, sin gasto.
- Se rompe cuando: nadie adopta la especificación ni la librería, o la migración post-cuántica
  de la OACI se aleja tanto que nadie lo necesita todavía.

### C. Producto de uso público

Play Store, soporte, iPhone, revisión de seguridad y cumplimiento como responsable de datos
bajo la Ley 21.719. Además, un piloto con un verificador real en Chile.

- Cuesta: meses y dinero (auditoría, cuentas de desarrollador, soporte). Compite con
  productos que ya existen y tienen equipos.
- Se rompe cuando: no aparece un verificador dispuesto a aceptarlo.

Hay una cuarta opción que no propongo: competir comercialmente con ZKPassport. La 0003 ya la
descartó, y lo único que cambió es que la versión funciona, no el mercado.

## Recomendación

**B.** En términos del proyecto: la app ya demuestra que la idea funciona con una cédula
real. Lo que la vuelve un avance no es tener más usuarios, sino que otros puedan reusar la
parte post-cuántica, y eso no depende de convencer a ningún comercio. El rediseño accesible
sigue valiendo como demostración que cualquiera puede probar.

C sólo se justifica con un verificador concreto. Su disparador está abajo.

## Qué la invalidaría

- **Un verificador real se compromete a usarlo** (un comercio, un evento, una institución).
  Ahí conviene C, empezando por un piloto.
- **En un teléfono de gama media la prueba tarda más de 10 minutos o se cae por memoria.**
  Ahí el uso público no es viable con este probador, sea cual sea la opción, y gana A hasta
  que cambie el probador.
- **ZKPassport, Self o la UE publican una prueba post-cuántica para documentos con chip.**
  La diferencia de B desaparece y gana A.

## Costo de equivocarse

Bajo y reversible: todo es open source y nada impide pasar de B a C. El riesgo real es
pasar meses en C sin un verificador, y se notaría porque no habría nadie con quien probar
el piloto.
