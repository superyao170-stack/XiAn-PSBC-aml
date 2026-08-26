from __future__ import annotations

import importlib
import os
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch


WORKER_ROOT = Path(__file__).resolve().parent
MODULE_ROOT = WORKER_ROOT / "xian_modules"
if str(MODULE_ROOT) not in sys.path:
    sys.path.insert(0, str(MODULE_ROOT))


class WorkerConfigurationTests(unittest.TestCase):
    def test_worker_env_replaces_blank_process_value(self) -> None:
        import framework_extraction

        with tempfile.TemporaryDirectory() as directory:
            env_file = Path(directory) / ".env"
            env_file.write_text(
                "DEEPSEEK_API_KEY=worker-file-key\n",
                encoding="utf-8",
            )
            with patch.dict(os.environ, {"DEEPSEEK_API_KEY": ""}):
                framework_extraction._load_worker_environment(env_file)
                self.assertEqual("worker-file-key", os.environ["DEEPSEEK_API_KEY"])

    def test_deepseek_client_reads_worker_environment(self) -> None:
        with patch.dict(
            os.environ,
            {
                "DEEPSEEK_API_KEY": "test-worker-key",
                "DEEPSEEK_BASE_URL": "https://model.example.test",
                "DEEPSEEK_CHAT_MODEL": "test-chat-model",
            },
        ):
            import datagraph_bank.llm as llm

            llm = importlib.reload(llm)
            client = llm.DeepSeekLLMClient(enabled=True)
            try:
                self.assertEqual("test-worker-key", client.api_key)
                self.assertEqual("https://model.example.test", client.base_url)
                self.assertEqual("test-chat-model", client.chat_model)
                self.assertNotEqual(
                    "DEEPSEEK_API_KEY is not configured in the worker environment",
                    client.init_error,
                )
            finally:
                client.close()

    def test_json_parser_accepts_fences_commentary_and_multiple_objects(self) -> None:
        import datagraph_bank.llm as llm

        client = llm.DeepSeekLLMClient(enabled=False)
        self.assertEqual(
            {"paragraphs": []},
            client._parse_json_object('```json\n{"paragraphs": []}\n```'),
        )
        self.assertEqual(
            {"paragraphs": []},
            client._parse_json_object(
                '生成结果如下：{"paragraphs": []}\n补充信息：{"ignored": true}'
            ),
        )

    def test_json_parser_rejects_truncated_objects(self) -> None:
        import datagraph_bank.llm as llm

        client = llm.DeepSeekLLMClient(enabled=False)
        self.assertIsNone(client._parse_json_object('{"paragraphs": ['))

    def test_governed_workflow_retries_invalid_model_output(self) -> None:
        from aml_analysis_workflow.config import WorkflowConfig

        self.assertEqual(3, WorkflowConfig.load().model_attempts)


if __name__ == "__main__":
    unittest.main()
