class FraudWorkflowError(Exception):
    """Base class for expected workflow failures."""


class InputValidationError(FraudWorkflowError):
    """Input case files do not satisfy the workflow contract."""


class ModelResponseError(FraudWorkflowError):
    """A model response cannot be parsed or violates the output contract."""


class RiskExtractionRejectedError(FraudWorkflowError):
    """Risk extraction still fails review after revision rounds."""


class ReportReviewRejectedError(FraudWorkflowError):
    """Report still fails review after targeted rewrite rounds."""
