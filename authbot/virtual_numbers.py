"""WaysGram's internal number formats, not public telephone routing."""
import re

SECRET_NUMBERS = frozenset({"+111", "+999", "+0", "+333", "+666"})
E164_NUMBER = re.compile(r"\+[1-9][0-9]{7,14}")


def is_virtual_number(value: object) -> bool:
    return isinstance(value, str) and (value in SECRET_NUMBERS or E164_NUMBER.fullmatch(value) is not None)
