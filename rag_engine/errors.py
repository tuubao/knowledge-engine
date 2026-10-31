class EngineError(Exception):
    """可跨语言传递的业务错误，不把第三方异常或密钥写入响应。"""

    def __init__(self, code: str, message: str, status: int = 503, retryable: bool = False):
        super().__init__(message)
        self.code = code
        self.message = message
        self.status = status
        self.retryable = retryable

    def payload(self, request_id: str) -> dict:
        return {"code": self.code, "message": self.message,
                "request_id": request_id, "retryable": self.retryable}
