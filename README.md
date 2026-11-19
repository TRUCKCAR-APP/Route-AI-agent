# Route-AI-agent

Planeación de rutas para camión con HERE Routing v8 (altura, peso bruto, peso por eje, ejes y peajes en COP) e interpretación con IA de las alertas de la comunidad. Escucha en el puerto 8086 y se llama a través del api-gateway (`/api/v1/routes/**`).

## Endpoints

| | Ruta | Uso |
|---|---|---|
| `POST` | `/api/v1/routes/plan` | Plan del viaje antes de salir: geocodifica las ciudades, pide alternativas a HERE, las valida contra Routing-Restrictions y las alertas activas, elige la mejor y guarda el historial |
| `POST` | `/api/v1/routes/reroute` | Recálculo durante el viaje (ver abajo) |
| `GET` | `/api/v1/routes/places?q=&lat=&lng=&limit=` | Destinos por nombre para la navegación libre («¿A dónde va?» o «necesito ir a…» por voz): HERE Geocoding & Search v7 (`/discover`) cerca de la posición, dentro de Colombia y en español. Devuelve `id`, `title`, `address`, `category`, `latitude`, `longitude` (la entrada vehicular si HERE la conoce) y `distanceMeters`. 2 a 120 caracteres; hasta 8 resultados; sin clave, 503 |
| `GET` | `/api/v1/routes/history/{plate}` | Historial de planes de una placa (solo `COORDINADOR` o `ADMIN`) |

### `POST /api/v1/routes/reroute`

Lo usa la navegación del conductor cuando pide otra ruta ("hay mucho tráfico, quiero ir por otra ruta") o cuando aparece un bloqueo en su camino.

**Qué hace:**
- Calcula las rutas desde la **posición actual** hasta el destino, con el **perfil del camión** y las **zonas que debe evitar**.
- Hace una sola llamada a HERE: no geocodifica, no consulta alertas y no guarda historial.
- Devuelve primero las rutas aptas para el camión.
- **Duración con tráfico:** HERE calcula con la hora de salida actual.

**Si falla:** sin `HERE_API_KEY`, o si HERE no responde, responde **503**. La app vuelve entonces a su recálculo de respaldo.

**Petición:**

```json
{ "originLatitude": 4.70, "originLongitude": -74.05, "destinationLatitude": 5.53, "destinationLongitude": -73.36,
  "heightMeters": 4.1, "grossWeightTons": 32, "truckType": "TRACTOMULA_3S3",
  "avoid": [ { "latitude": 4.75, "longitude": -74.04, "radiusMeters": 300 } ] }
```

**Campos de la petición:**
- `axleCount` y `truckType` son opcionales. Los ejes salen del tipo de camión si no se envían.
- `avoid` admite hasta 10 zonas. Cada una se envía a HERE como un cuadrado `avoid[areas]=bbox:…` con su radio, 300 m por defecto.

**Respuesta:** `{"provider":"HERE","routes":[{distanceMeters, durationSeconds, geometry:[[lat,lng]…], steps:[…], violations:[…], tollCost}]}`.
- **`steps`:** las maniobras en español (`action`, `instruction`, `direction`, `severity`, `exit`, punto y distancias).
- **`violations`:** las restricciones del camión que la ruta no cumple; queda vacía si la ruta es apta.

## Configuración

| Variable | Uso |
|---|---|
| `HERE_API_KEY` | Clave de HERE (solo en el `.env`, nunca en el código). Sin ella, `/plan` y `/reroute` responden 503 |
| `AZURE_OPENAI_ENDPOINT`, `AZURE_OPENAI_KEY`, `AZURE_OPENAI_DEPLOYMENT` | Interpretación de alertas; sin ellas se usan reglas |
| `TRUCKCAR_AI_PROVIDER` | `azure` (por defecto), `bedrock` o `rules` |

## Pruebas

```bash
./mvnw clean verify     # PostgreSQL y Kafka locales (los da el CI); HereLiveRoutingTest se salta sin HERE_API_KEY
```
