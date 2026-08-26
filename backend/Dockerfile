
FROM python:3.11-slim AS model-downloader

ARG EMBEDDING_MODEL_ID=BAAI/bge-small-zh-v1.5
ARG RERANKER_MODEL_ID=BAAI/bge-reranker-v2-m3

RUN pip install --no-cache-dir "modelscope>=1.20,<2" \
    && modelscope download --model "${EMBEDDING_MODEL_ID}" --exclude pytorch_model.bin --local_dir /models/bge-small-zh-v1.5 \
    && modelscope download --model "${RERANKER_MODEL_ID}" --local_dir /models/bge-reranker-v2-m3 \
    && test -s /models/bge-small-zh-v1.5/model.safetensors \
    && test -s /models/bge-reranker-v2-m3/model.safetensors

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends python3 python3-venv libgomp1 \
    && rm -rf /var/lib/apt/lists/*

COPY workers/requirements.txt /tmp/aml-worker-requirements.txt
RUN python3 -m venv /opt/aml-venv \
    && /opt/aml-venv/bin/pip install --no-cache-dir --upgrade pip \
    && /opt/aml-venv/bin/pip install --no-cache-dir --index-url https://download.pytorch.org/whl/cpu torch==2.5.1 \
    && /opt/aml-venv/bin/pip install --no-cache-dir -r /tmp/aml-worker-requirements.txt \
    && rm -f /tmp/aml-worker-requirements.txt

COPY --from=model-downloader /models /opt/models
COPY workers/aml-intelligence /app/workers/aml-intelligence
COPY workers/structured-case-identification /app/workers/structured-case-identification
COPY backend/target/bankgraph-backend-*.jar bankgraph-backend.jar

ENV WORKER_PYTHON=/opt/aml-venv/bin/python \
    AML_INTELLIGENCE_WORKER_ROOT=/app/workers/aml-intelligence \
    STRUCTURED_CASE_WORKER_ROOT=/app/workers/structured-case-identification \
    AML_EMBEDDING_MODEL=/opt/models/bge-small-zh-v1.5 \
    AML_RERANKER_MODEL=/opt/models/bge-reranker-v2-m3 \
    AML_MODEL_LOCAL_FILES_ONLY=true \
    STRUCTURED_CASE_EMBEDDING_MODEL=/opt/models/bge-small-zh-v1.5 \
    STRUCTURED_CASE_RERANKER_MODEL=/opt/models/bge-reranker-v2-m3 \
    STRUCTURED_CASE_MODEL_LOCAL_FILES_ONLY=true \
    STRUCTURED_CASE_SIMILARITY_TIMEOUT_SECONDS=180 \
    STRUCTURED_CASE_SEMANTIC_TIMEOUT_SECONDS=90 \
    HF_HUB_OFFLINE=1 \
    TRANSFORMERS_OFFLINE=1

EXPOSE 3030
ENTRYPOINT ["java", "-jar", "bankgraph-backend.jar"]
