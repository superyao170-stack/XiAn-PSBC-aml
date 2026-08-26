import gc
import json
import os
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import torch
from sentence_transformers import SentenceTransformer
from transformers import AutoModelForSequenceClassification, AutoTokenizer


MODEL_PATHS = {
    "BAAI/bge-m3": os.environ.get("BGE_M3_MODEL", "/opt/models/bge-m3"),
    "BAAI/bge-small-zh-v1.5": os.environ.get(
        "BGE_SMALL_MODEL", "/opt/models/bge-small-zh-v1.5"
    ),
    "BAAI/bge-reranker-v2-m3": os.environ.get(
        "BGE_RERANKER_MODEL", "/opt/models/bge-reranker-v2-m3"
    ),
}
PORT = int(os.environ.get("BGE_MODELS_PORT", "8080"))
MODEL_LOCK = threading.RLock()
LOADED = {"model_id": None, "model": None, "tokenizer": None}


def unload_model():
    LOADED.update(model_id=None, model=None, tokenizer=None)
    gc.collect()


def load_embedding_model(model_id):
    with MODEL_LOCK:
        if LOADED["model_id"] != model_id:
            unload_model()
            LOADED["model"] = SentenceTransformer(
                MODEL_PATHS[model_id], device="cpu", local_files_only=True
            )
            LOADED["model_id"] = model_id
        return LOADED["model"]


def load_reranker():
    model_id = "BAAI/bge-reranker-v2-m3"
    with MODEL_LOCK:
        if LOADED["model_id"] != model_id:
            unload_model()
            model_path = MODEL_PATHS[model_id]
            LOADED["tokenizer"] = AutoTokenizer.from_pretrained(
                model_path, local_files_only=True
            )
            LOADED["model"] = AutoModelForSequenceClassification.from_pretrained(
                model_path, local_files_only=True
            ).to("cpu")
            LOADED["model"].eval()
            LOADED["model_id"] = model_id
        return LOADED["tokenizer"], LOADED["model"]


class Handler(BaseHTTPRequestHandler):
    def send_json(self, status, payload):
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def read_json(self):
        length = int(self.headers.get("Content-Length", "0"))
        return json.loads(self.rfile.read(length))

    def do_GET(self):
        if self.path == "/health":
            self.send_json(
                200,
                {
                    "status": "ok",
                    "models": {
                        model_id: {
                            "path": model_path,
                            "available": os.path.isdir(model_path),
                            "loaded": LOADED["model_id"] == model_id,
                        }
                        for model_id, model_path in MODEL_PATHS.items()
                    },
                },
            )
            return
        self.send_json(404, {"error": "not found"})

    def do_POST(self):
        try:
            if self.path in ("/v1/embeddings", "/embedding"):
                self.handle_embeddings(self.read_json())
                return
            if self.path in ("/v1/rerank", "/rerank"):
                self.handle_rerank(self.read_json())
                return
            self.send_json(404, {"error": "not found"})
        except Exception as exc:
            self.send_json(400, {"error": str(exc)})

    def handle_embeddings(self, payload):
        model_id = payload.get("model", "BAAI/bge-m3")
        if model_id not in ("BAAI/bge-m3", "BAAI/bge-small-zh-v1.5"):
            raise ValueError("unsupported embedding model")
        inputs = payload.get("input", payload.get("content"))
        texts = [inputs] if isinstance(inputs, str) else inputs
        if not isinstance(texts, list) or not all(isinstance(item, str) for item in texts):
            raise ValueError("input must be a string or a list of strings")
        with MODEL_LOCK:
            model = load_embedding_model(model_id)
            vectors = model.encode(texts, normalize_embeddings=True).tolist()
        self.send_json(
            200,
            {
                "object": "list",
                "model": model_id,
                "data": [
                    {"object": "embedding", "index": index, "embedding": vector}
                    for index, vector in enumerate(vectors)
                ],
            },
        )

    def handle_rerank(self, payload):
        query = payload.get("query")
        documents = payload.get("documents")
        if not isinstance(query, str) or not isinstance(documents, list):
            raise ValueError("query must be a string and documents must be a list")
        if not all(isinstance(document, str) for document in documents):
            raise ValueError("every document must be a string")
        with MODEL_LOCK:
            tokenizer, model = load_reranker()
            encoded = tokenizer(
                [[query, document] for document in documents],
                padding=True,
                truncation=True,
                max_length=512,
                return_tensors="pt",
            )
            with torch.no_grad():
                scores = torch.sigmoid(model(**encoded).logits.view(-1).float()).tolist()
        results = [
            {"index": index, "score": score, "document": documents[index]}
            for index, score in enumerate(scores)
        ]
        results.sort(key=lambda item: item["score"], reverse=True)
        self.send_json(
            200,
            {
                "model": "BAAI/bge-reranker-v2-m3",
                "results": results,
            },
        )

    def log_message(self, fmt, *args):
        print(f"{self.address_string()} - {fmt % args}", flush=True)


if __name__ == "__main__":
    print(f"BGE model service listening on 0.0.0.0:{PORT}", flush=True)
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
