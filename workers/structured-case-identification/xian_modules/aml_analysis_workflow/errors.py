class AnalysisWorkflowError(RuntimeError):
    """Base error for a case that cannot be processed safely."""


class InputValidationError(AnalysisWorkflowError):
    """The CSV or one of its JSON columns violates the input contract."""


class ModelResponseError(AnalysisWorkflowError):
    """The model response is unavailable or violates the node contract."""


class ReviewRejectedError(AnalysisWorkflowError):
    """The generated case still fails review after targeted rewrites."""

