"""AML analysis text generation workflow."""

from .csv_input import read_case, read_cases
from .runner import run_csv_generation, run_single_generation, run_xlsx_generation
from .workflow import AnalysisWorkflow
from .xlsx_input import read_xlsx_cases

__all__ = [
    "AnalysisWorkflow",
    "read_case",
    "read_cases",
    "read_xlsx_cases",
    "run_single_generation",
    "run_csv_generation",
    "run_xlsx_generation",
]
__version__ = "0.1.0"
