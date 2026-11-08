# DECISION-AI-PROVIDER — Selección del proveedor de IA para Route-AI-agent

## Contexto

El microservicio `Route-AI-agent` interpreta alertas comunitarias de conductores colombianos para determinar si un corredor vial está bloqueado. La lógica de interpretación es intercambiable a través de la variable de entorno `TRUCKCAR_AI_PROVIDER`, que acepta tres valores: `azure`, `bedrock` y `rules`.

## Opciones evaluadas

| Proveedor | Requisito de credenciales | Disponibilidad en el laboratorio |
|-----------|--------------------------|----------------------------------|
| `azure`   | Suscripción de pago propia de Azure OpenAI | ❌ No disponible |
| `bedrock` | Credenciales temporales de AWS Academy Learner Lab | ✅ Disponible (LabRole) |
| `rules`   | Ninguno (cero configuración) | ✅ Siempre disponible |

## Decisión

### Entorno de laboratorio (AWS Academy Learner Lab)

Se establece `TRUCKCAR_AI_PROVIDER=bedrock` en el `docker-compose.yml` del lab. Amazon Bedrock está incluido en el crédito del Learner Lab y el rol `LabRole` ya tiene los permisos necesarios. Si las credenciales temporales expiran o el modelo no está habilitado, el `ModelAlertInterpreter` activará automáticamente el fallback a `rules` a través de Resilience4j (CircuitBreaker + Retry), de modo que ninguna alerta se pierde.

### Desarrollo local sin credenciales

Se establece `TRUCKCAR_AI_PROVIDER=rules` en el archivo `.env` local o en la configuración del IDE. El intérprete de reglas no depende de red ni de credenciales, ofrece latencia nula y permite ejecutar toda la suite de pruebas sin servicios externos.

### Azure OpenAI

Requiere una suscripción de pago independiente. No se recomienda para el laboratorio académico porque implica costos reales y configuración adicional (endpoint, API key, despliegue del modelo).

## Cadena de fallback

```
TRUCKCAR_AI_PROVIDER=bedrock
        │
        ▼
  BedrockAlertInterpreter
  (Retry → CircuitBreaker → TimeLimiter)
        │  fallo / timeout / 429 / sin credenciales
        ▼
  RuleBasedAlertInterpreter   ←─── TRUCKCAR_AI_PROVIDER=rules (directo)
```

La cadena es automática y transparente: el resultado siempre tiene la misma estructura (`AlertInterpretation`), con `source=RULES` cuando responden las reglas.

## Configuración recomendada

```yaml
# docker-compose.yml (lab)
environment:
  TRUCKCAR_AI_PROVIDER: bedrock
  AWS_REGION: us-east-1
  BEDROCK_MODEL_ID: amazon.nova-micro-v1:0
```

```properties
# .env (desarrollo local)
TRUCKCAR_AI_PROVIDER=rules
```
