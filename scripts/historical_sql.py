"""Read a named historical SQL section from the compressed test archive."""
from pathlib import Path

_ARCHIVE = Path(__file__).resolve().parents[1] / 'backend/src/test/resources/legacy-migrations.archive.archive'

def read(name: str) -> str:
    source = _ARCHIVE.read_text()
    marker = f'-- @legacy-migration {name}'
    start = source.index(marker) + len(marker)
    start = source.index('\n', start) + 1
    next_marker = source.find('\n-- @legacy-migration ', start)
    return source[start:] if next_marker < 0 else source[start:next_marker]
