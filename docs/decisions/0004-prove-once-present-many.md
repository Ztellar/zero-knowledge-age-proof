# 0004 — Probar una vez, presentar muchas

Fecha: 2026-10-25. Estado: aceptada. **Implementada en parte** (2026-10-26): registro con la
clave ML-DSA en el journal y una presentación de muestra dentro de la app. **Falta** el
verificador externo con desafío (etapa 5). Complementa la
[0003](0003-android-app-post-quantum-proof.md).

## Contexto

En el S25 la prueba sucinta tarda 64,7 s y usa hasta 2,47 GB
([mediciones](../measurements/stage-2-android.md)). Es demasiado lento y
pesado para hacerlo cada vez que alguien pide la edad.

Lo que se midió y no lo baja:

- **Verificar CSCA→DSC fuera de la prueba.** Los ciclos del programa bajan de 198.377 a
  122.622, pero con la paginación (47.061) suman 169.683. Eso no cabe en 2^17 = 131.072,
  así que la prueba sigue en un segmento de 2^18 y tarda lo mismo en el Mac: 23,4 s contra
  22,8 s, en la prueba compuesta. Además revelaría el DSC.
- **Segmentos más chicos.** Con 2^16 baja a 1,75 GB pero tarda 21 minutos
  ([etapa 1](../measurements/stage-1-desktop.md)).

Las dos firmas RSA-4096 ocupan 152.031 de los 198.377 ciclos del programa (77%). Ese es el
piso de RISC Zero con este circuito.

## Decisión

La prueba pesada se genera **una vez**, al registrar la cédula. Cada uso posterior cuesta
sólo una firma.

1. **Registro.** La app genera un par de claves ML-DSA (FIPS 204) del teléfono. La prueba
   STARK recibe la clave pública como entrada y la publica en el journal junto al `Claim`.
   Corre en segundo plano con una notificación de progreso.
2. **Presentación.** El verificador manda un desafío aleatorio. El teléfono responde con la
   prueba guardada y una firma ML-DSA del desafío.
3. **Verificación.** El verificador comprueba la prueba, que la CSCA del journal sea de
   Chile, que la fecha del journal sea reciente y que la firma del desafío corresponda a la
   clave del journal.

Sigue siendo post-cuántico en lo que controla el proyecto: STARK más ML-DSA.

## Costo aceptado

**Presentaciones enlazables entre verificadores.** Todos reciben la misma prueba y la misma
clave pública. Si dos verificadores juntan sus datos, saben que fue la misma persona, aunque
no sepan quién. Se documenta como limitación de la primera versión.

Formas de cerrarlo, ambas fuera de la primera versión:

- una clave por verificador, lo que obliga a una prueba por verificador;
- una prueba corta por uso que verifique la prueba guardada (composición de RISC Zero).
  Su costo no está medido.

## Preguntas abiertas

- **Dónde vive la clave ML-DSA.** *Resuelta 2026-10-26.* El Android Keystore genera claves
  ML-DSA desde la API 37 (`KeyProperties.KEY_ALGORITHM_ML_DSA_65`, "Added in API level 37"
  en la referencia de Android); el S25 está en la API 36. La app
  ([`DeviceKey`](../../android/app/src/main/kotlin/cl/zkage/app/DeviceKey.kt)) usa el
  Keystore en API 37+ y, si no,
  genera la clave ML-DSA-65 con Bouncy Castle desde una semilla de 32 bytes guardada cifrada
  con AES-256-GCM del Keystore. En el S25 esa clave AES quedó en StrongBox. Costo: al firmar,
  la clave está en la memoria de la app; la semilla cifrada no sirve en otro teléfono.
  **Android 17** (el S25 lo recibirá): la prueba guardada y su clave en software siguen
  sirviendo tras la actualización; la siguiente prueba usa una clave del Keystore y recién
  cuando queda guardada se borra la de software. La app elige la clave por el hash que
  trae la prueba, así que una prueba fallida a mitad del cambio no rompe la anterior. En el
  emulador de Android 17 la KeyMint es `SoftKeymaster`, que no genera ML-DSA
  (`TRANSACTION_FAILED` con `Algorithm(ML_DSA)`, `MlDsaVariant(ML_DSA_65)`): la app vuelve a
  software y dice por qué. **La rama del Keystore sigue sin probarse en un teléfono con
  ML-DSA**, ni la migración; se verá en el S25 con Android 17.
  El journal publica el SHA-256 de la clave pública (SubjectPublicKeyInfo DER), no la
  clave entera (1.974 B): la presentación la manda junto con la firma.
- **Vigencia de la prueba.** El journal trae la fecha en que se generó. Cuánto tiempo la
  acepta un verificador lo decide el verificador; la sugerencia es 12 meses, como la
  revalidación de la [0002](0002-device-registry.md).
