"""WaysGram's internal number formats, not public telephone routing."""
import re

# Секретные номера: короткие «демо»-логины, которые не являются настоящими
# телефонными линиями.
SECRET_NUMBERS = frozenset({"+111", "+999", "+0", "+333", "+666"})

# Секретное пространство — это префикс секретного номера и любые цифры после него:
# так «+0 000 0000» (+00000000) остаётся секретным номером, а не ошибкой формата.
# Раньше принимались только сами «+0», «+111», …  и такие номера не удавалось
# ни создать в боте, ни ввести на экране входа в приложение.
SECRET_PREFIXES = ("+0", "+111", "+333", "+666", "+999")
SECRET_SPACE = re.compile(r"\+(?:0|111|333|666|999)[0-9]{0,12}")

E164_NUMBER = re.compile(r"\+[1-9][0-9]{7,14}")


def is_secret_number(value: object) -> bool:
    """Номер целиком лежит в секретном пространстве (с цифрами после префикса или без)."""
    return isinstance(value, str) and SECRET_SPACE.fullmatch(value) is not None


def normalize_number(value: object) -> str:
    """Убирает форматирование номера: «+0 000 0000» → «+00000000».

    Клиент присылает номер уже без пробелов, но бот и внешние вызовы могут передать
    его как ввёл человек; сервер не должен на этом спотыкаться.
    """
    if value is None:
        return ""
    digits = re.sub(r"\D", "", str(value))
    return "+" + digits if digits else ""


def is_virtual_number(value: object) -> bool:
    return isinstance(value, str) and (
        value in SECRET_NUMBERS
        or SECRET_SPACE.fullmatch(value) is not None
        or E164_NUMBER.fullmatch(value) is not None
    )
