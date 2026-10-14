from __future__ import annotations

from pathlib import Path

from app.natro.ini_adapter import NatroIni


def test_reads_values_and_types(tmp_path: Path) -> None:
    ini_path = tmp_path / "nm_config.ini"
    ini_path.write_text(
        "[Status]\n"
        "SessionTotalHoney=1,234,567\n"
        "HoneyAverage=6.189 B\n"
        "TotalDisconnects=3\n"
        "HiveLevel=20\n"
        "\n"
        "[Boost]\n"
        "StrawberryBoosterCheck=1\n"
        "PineTreeBoosterCheck=0\n",
        encoding="utf-8",
    )
    ini = NatroIni(ini_path)
    assert ini.exists
    assert ini.get("Status", "SessionTotalHoney") == "1,234,567"
    assert ini.get_int("Status", "TotalDisconnects") == 3
    assert ini.get_int("Status", "HiveLevel") == 20
    assert ini.get("Boost", "StrawberryBoosterCheck") == "1"
    assert ini.get("Status", "Missing") is None
    assert ini.get("Status", "Missing", default="x") == "x"


def test_preserves_key_case(tmp_path: Path) -> None:
    ini_path = tmp_path / "nm_config.ini"
    ini_path.write_text("[Status]\nBotToken=abc.def.ghi\n", encoding="utf-8")
    assert NatroIni(ini_path).get("Status", "BotToken") == "abc.def.ghi"


def test_missing_file_is_safe(tmp_path: Path) -> None:
    ini = NatroIni(tmp_path / "nope.ini")
    assert not ini.exists
    assert ini.get("Status", "X") is None
