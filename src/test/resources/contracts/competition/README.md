# Muestras del productor de resultados

Capturadas el 2026-10-17 mediante `CompetitionProducerContractTest`: publicador y conversor reales de la copia local de Competencia, con `RabbitTemplate` simulado y datos ficticios. No se obtuvo tráfico del broker ni se usaron credenciales.

`result-v1.json` y `result-v2.json` conservan los bytes JSON generados por el conversor; los archivos `-properties.json` registran content type, codificación y headers emitidos. `provenance.json` identifica las fuentes por SHA-256. La generación posterior escribe únicamente en `target/contracts/competition/`, sin reemplazar estas muestras.

La suite habitual valida las muestras sin el proyecto vecino. La comprobación opcional compila el productor actual y valida sus mensajes directamente. Procedimiento y límites: [guía de contrato](../../../../../docs/competition-contract-step.md).
