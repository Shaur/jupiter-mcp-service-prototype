import json
import base64

class Report:
    """Аккумулятор результата правила. Последняя строка скрипта — `report`.

    Рендерится в JSON: {"valid": true} или {"valid": false, "message": "..."}.
    """

    def __init__(self):
        self.valid = True
        self.message = None

    def valid(self):
        """Отметить правило пройденным (сбрасывает предыдущие нарушения)."""
        self.valid = True
        self.message = None

    def invalid(self, message):
        """Отметить правило нарушенным с причиной."""
        self.valid = False
        self.message = message

    def _repr_pretty_(self, p, cycle):
        data = {"valid": self.valid}
        if self.message is not None:
            data["message"] = self.message
        p.text(json.dumps(data, ensure_ascii=False))

def load_payload(b64):
    """Десериализует payload из base64-JSON; None, если payload не передан."""
    if b64 is None:
        return None
    return json.loads(base64.b64decode(b64).decode("utf-8"))

report = Report()
