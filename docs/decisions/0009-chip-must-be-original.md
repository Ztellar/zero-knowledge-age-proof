# 0009 — Un chip que no demuestra ser original detiene la prueba

Fecha: 2026-10-29. Estado: **aceptada e implementada en la app** (`Gate.kt`). Reemplaza para la
app el punto 3 de la [0006](0006-active-authentication-in-proof.md).

## Contexto

La 0006 permitía generar la prueba sin Active Authentication, con
`chip_authenticated = false`, cuando la cédula no tenía DG15 o usaba una clave que no fuera
P-256. Pero el verificador web rechaza esas pruebas salvo con `--allow-no-chip`. Para una
persona cualquiera eso significaba esperar entre uno y tres minutos por una prueba que
después le rechazan.

## Decisión

La app no sigue después de leer la cédula si el chip no demuestra ser original, y lo dice
en ese momento, antes de comparar la cara. Cuenta como no original:

- el chip no tiene DG15;
- DG15 no es el que firma el SOD;
- el chip no respondió;
- la firma del chip no es válida.

Si la firma es válida pero no es ECDSA P-256 con SHA-256, la cédula es original pero la
prueba no puede revisarla. También se detiene, con otro mensaje: "Este tipo de cédula
todavía no es compatible".

El programa del zkVM no cambia: sigue aceptando `chip_authenticated = false`. La app es la
que deja de generar esas pruebas.

## Por qué

Lo dijo el usuario el 2026-10-29: "un chip no original no debe ni pasar más allá de su
comprobación". Además, ninguna prueba sin chip pasa el verificador web por omisión.

## Qué se descartó

- **Generarla igual y avisar**, como antes. La persona espera minutos por una prueba que el
  verificador rechaza.

## Qué la invalidaría

- Que una cédula chilena real use Active Authentication con RSA o con otra curva. Hoy esas
  cédulas quedan fuera; habría que soportarlas en el programa de la prueba.
- Que un verificador quiera aceptar pruebas sin chip. Entonces la app tendría que saber qué
  exige cada verificador antes de generar la prueba.
