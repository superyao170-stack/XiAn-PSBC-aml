FROM datagraph-bank-backend:1.5.0

ARG BGE_M3_REVISION=e44369c5623cc146f016da906583db4ee0e3488d

ENV HF_HUB_OFFLINE=0 \
    TRANSFORMERS_OFFLINE=0

RUN /opt/aml-venv/bin/modelscope download \
      --model BAAI/bge-m3 \
      --revision "${BGE_M3_REVISION}" \
      --exclude pytorch_model.bin "onnx/*" "*.jpg" "*.webp" README.md \
      --local_dir /opt/models/bge-m3

RUN set -eux; \
    model_url="https://www.modelscope.cn/models/BAAI/bge-m3/resolve/${BGE_M3_REVISION}/pytorch_model.bin"; \
    model_size=2271145830; \
    chunk_size=67108864; \
    last_part=$(( (model_size - 1) / chunk_size )); \
    mkdir -p /tmp/bge-m3-parts; \
    export model_url model_size chunk_size; \
    seq 0 "$last_part" | xargs -P 4 -I '{}' sh -c '\
      part="$1"; \
      start=$((part * chunk_size)); \
      end=$((start + chunk_size - 1)); \
      if [ "$end" -ge "$model_size" ]; then end=$((model_size - 1)); fi; \
      output=$(printf "/tmp/bge-m3-parts/part-%04d" "$part"); \
      curl --fail --silent --show-error --location --retry 20 --retry-all-errors \
        --connect-timeout 30 --max-time 1800 \
        --range "${start}-${end}" --output "$output" "$model_url"\
    ' _ '{}'; \
    cat /tmp/bge-m3-parts/part-* > /opt/models/bge-m3/pytorch_model.bin; \
    echo "b5e0ce3470abf5ef3831aa1bd5553b486803e83251590ab7ff35a117cf6aad38  /opt/models/bge-m3/pytorch_model.bin" | sha256sum -c -; \
    rm -rf /tmp/bge-m3-parts

RUN /opt/aml-venv/bin/python -c \
      'import torch; from safetensors.torch import save_file; source="/opt/models/bge-m3/pytorch_model.bin"; target="/opt/models/bge-m3/model.safetensors"; state=torch.load(source, map_location="cpu", weights_only=True); save_file(state, target)' \
    && test -s /opt/models/bge-m3/model.safetensors \
    && rm -f /opt/models/bge-m3/pytorch_model.bin

COPY environment/docker/bge_models_server.py /opt/bge-models/server.py

ENV HF_HUB_OFFLINE=1 \
    TRANSFORMERS_OFFLINE=1 \
    BGE_M3_MODEL=/opt/models/bge-m3 \
    BGE_SMALL_MODEL=/opt/models/bge-small-zh-v1.5 \
    BGE_RERANKER_MODEL=/opt/models/bge-reranker-v2-m3 \
    BGE_MODELS_PORT=8080

EXPOSE 8080

ENTRYPOINT ["/opt/aml-venv/bin/python", "/opt/bge-models/server.py"]
