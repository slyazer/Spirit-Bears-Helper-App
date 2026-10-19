"""Tests for the bridge launcher (`run.py`).

In particular: MM_TLS_CERT_FILE / MM_TLS_KEY_FILE must actually reach uvicorn.
SECURITY.md advertises optional TLS, and before this was wired the env vars were
accepted by Settings but silently ignored.
"""

from __future__ import annotations

import logging
from types import SimpleNamespace
from unittest.mock import patch

import pytest

import run


def _settings(**overrides) -> SimpleNamespace:
    base = {
        "host": "127.0.0.1",
        "port": 8787,
        "tls_cert_file": None,
        "tls_key_file": None,
    }
    base.update(overrides)
    return SimpleNamespace(**base)


def test_serves_plain_http_when_no_tls_is_configured():
    with patch.object(run, "get_settings", return_value=_settings()), patch.object(
        run.uvicorn, "run"
    ) as mock_run:
        run.main()

    kwargs = mock_run.call_args.kwargs
    assert kwargs["host"] == "127.0.0.1"
    assert kwargs["port"] == 8787
    assert "ssl_certfile" not in kwargs
    assert "ssl_keyfile" not in kwargs


def test_serves_https_when_cert_and_key_are_both_set(tmp_path):
    cert = tmp_path / "bridge.pem"
    key = tmp_path / "bridge.key"
    cert.write_text("dummy cert")
    key.write_text("dummy key")
    settings = _settings(tls_cert_file=str(cert), tls_key_file=str(key))
    with patch.object(run, "get_settings", return_value=settings), patch.object(
        run.uvicorn, "run"
    ) as mock_run:
        run.main()

    kwargs = mock_run.call_args.kwargs
    assert kwargs["ssl_certfile"] == str(cert)
    assert kwargs["ssl_keyfile"] == str(key)


def test_partial_tls_config_is_ignored_with_a_warning(caplog):
    settings = _settings(tls_cert_file="C:/certs/bridge.pem")
    with caplog.at_level(logging.WARNING, logger="macro_monitor.bridge"):
        with patch.object(run, "get_settings", return_value=settings), patch.object(
            run.uvicorn, "run"
        ) as mock_run:
            run.main()

    kwargs = mock_run.call_args.kwargs
    assert "ssl_certfile" not in kwargs
    assert any("both be set" in record.message for record in caplog.records)


def test_missing_cert_file_is_a_clear_startup_error(tmp_path):
    settings = _settings(
        tls_cert_file=str(tmp_path / "does-not-exist.pem"),
        tls_key_file=str(tmp_path / "also-missing.key"),
    )
    with pytest.raises(RuntimeError, match="MM_TLS_CERT_FILE"):
        run._tls_kwargs(settings)


def test_missing_key_file_is_a_clear_startup_error(tmp_path):
    cert = tmp_path / "bridge.pem"
    cert.write_text("dummy")
    settings = _settings(
        tls_cert_file=str(cert),
        tls_key_file=str(tmp_path / "missing.key"),
    )
    with pytest.raises(RuntimeError, match="MM_TLS_KEY_FILE"):
        run._tls_kwargs(settings)


def test_main_exits_nonzero_when_tls_files_are_invalid(tmp_path):
    settings = _settings(
        tls_cert_file=str(tmp_path / "missing.pem"),
        tls_key_file=str(tmp_path / "missing.key"),
    )
    with patch.object(run, "get_settings", return_value=settings), patch.object(
        run.uvicorn, "run"
    ) as mock_run:
        with pytest.raises(SystemExit) as excinfo:
            run.main()

    assert excinfo.value.code == 2
    mock_run.assert_not_called()


def test_existing_readable_cert_and_key_are_accepted(tmp_path):
    cert = tmp_path / "bridge.pem"
    key = tmp_path / "bridge.key"
    cert.write_text("dummy cert")
    key.write_text("dummy key")
    settings = _settings(tls_cert_file=str(cert), tls_key_file=str(key))

    kwargs = run._tls_kwargs(settings)

    assert kwargs == {"ssl_certfile": str(cert), "ssl_keyfile": str(key)}
