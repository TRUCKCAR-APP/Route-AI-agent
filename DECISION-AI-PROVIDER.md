# DECISION-AI-PROVIDER — Selección y arquitectura del proveedor de IA para Route-AI-agent

## Contexto

El microservicio `Route-AI-agent` interpreta reportes comunitarios y alertas en lenguaje natural de conductores para determinar si un corredor vial está bloqueado. La plataforma implementa un desacoplamiento mediante la interfaz `AlertInterpreter` y la variable de entorno `TRUCKCAR_AI_PROVIDER`, soportando tres modalidades: `azure`, `bedrock` y `rules`.

---

## Proveedores Evaluados y Configuración Oficial

| Proveedor | Modelo / Motor | Rol en la Plataforma | Estado en Producción / Lab |
| :--- | :--- | :--- | :--- |
| **`azure`** | Azure OpenAI (`gpt-4.1-mini`) | **Proveedor Oficial Titular** | Desplegado en EKS y Docker Compose por defecto (DI #21, Route-AI #6). |
| **`bedrock`** | Amazon Bedrock (`amazon.nova-micro-v1:0`) | **Alternativa Multicloud / Lab AWS** | Soportado para entornos AWS Academy Learner Lab con rol `LabRole`. |
| **`rules`** | `RuleBasedAlertInterpreter` (Regex + Diccionario) | **Fallback Determinista / Offline** | Cero dependencias externas; activo por contingencia y en CI. |

---

## Decisión de Arquitectura

### 1. Proveedor Titular del Laboratorio y Despliegue: Azure OpenAI (`azure`)
En las ramas `main` y en los despliegues de Kubernetes (EKS) y Docker Compose, la plataforma utiliza por defecto **Azure OpenAI con el modelo `gpt-4.1-mini`**:
* Configuración estandarizada mediante `AZURE_OPENAI_ENDPOINT`, `AZURE_OPENAI_KEY` y `AZURE_OPENAI_DEPLOYMENT_NAME` (por defecto `gpt-4.1-mini`).
* Proporciona alta velocidad de inferencia, soporte nativo de JSON estructurado y alineación con los manifiestos de Kubernetes en `k8s/overlays/aws-eks-lab/platform-config.yaml`.

### 2. Alternativa Multicloud: Amazon Bedrock (`bedrock`)
Para entornos donde se cuente con créditos o infraestructura de AWS Academy Learner Lab:
* Se activa configurando `TRUCKCAR_AI_PROVIDER=bedrock`.
* Utiliza el modelo `amazon.nova-micro-v1:0` en la región `us-east-1` utilizando las credenciales IAM temporales (`LabRole`).
* Si las credenciales temporales expiran o el servicio presenta indisponibilidad, Resilience4j conmuta inmediatamente al fallback.

### 3. Modo Offline, Pruebas y Contingencia: Intérprete por Reglas (`rules`)
* Se activa con `TRUCKCAR_AI_PROVIDER=rules` o de forma automática cuando el modelo configurado (Azure o Bedrock) falla.
* Diseñado con un analizador de expresiones regulares optimizado para lenguaje y jerga de transportadores colombianos (*"derrumve"*, *"acidente"*, *"pela'os de policía"*, *"La Línea"*).
* Cero latencia de red, cero costo y disponibilidad garantizada al 100%.

---

## Cadena de Resiliencia y Fallback

```
   TRUCKCAR_AI_PROVIDER=azure                      TRUCKCAR_AI_PROVIDER=bedrock
                │                                               │
                ▼                                               ▼
     AzureOpenAiAlertInterpreter                    BedrockAlertInterpreter
       (gpt-4.1-mini en Azure)                   (nova-micro-v1:0 en Bedrock)
                │                                               │
                └───────────────────────┬───────────────────────┘
                                        │
                         Fallo / Timeout / 429 / 503
                                        │
                                        ▼
                           RuleBasedAlertInterpreter
                           (Resilience4j CircuitBreaker)
                                        │
                                        ▼
                      AlertInterpretation unificada (RULES)
```

Ambos proveedores y el clasificador por reglas emiten exactamente el mismo contrato (`AlertInterpretation`):
* `isCorridorBlocked`: boolean
* `affectedCorridor`: String canónico
* `severity`: String (`CRITICA`, `ALTA`, `MEDIA`, `BAJA`)
* `confidenceScore`: double (0 a 1)
* `source`: String (`AZURE_OPENAI`, `BEDROCK` o `RULES`)
* `alertType`: String (`DERRUMBE`, `ACCIDENTE`, `CIERRE`, `RETEN`, `BASCULA`, `TRAFICO`)
* `voiceText`: String con locución para lectura por voz (TTS)

---

## Configuración en Despliegue

```yaml
# docker-compose.yml / k8s platform-config (por defecto)
environment:
  TRUCKCAR_AI_PROVIDER: azure
  AZURE_OPENAI_ENDPOINT: ${AZURE_OPENAI_ENDPOINT}
  AZURE_OPENAI_KEY: ${AZURE_OPENAI_KEY}
  AZURE_OPENAI_DEPLOYMENT_NAME: gpt-4.1-mini
```

```properties
# .env (desarrollo local / pruebas sin conexión externa)
TRUCKCAR_AI_PROVIDER=rules
```
