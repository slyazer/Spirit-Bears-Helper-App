"""Admin CLI for the Macro Monitor bridge.

Run from the ``bridge`` folder with the venv Python, e.g.::

    python -m app.cli create-user alice
    python -m app.cli pair
    python -m app.cli list-users

The Windows setup wizard calls these commands so a user never has to touch a
JSON file or a token by hand.
"""

from __future__ import annotations

import argparse
import getpass
import sys
from pathlib import Path

from .config import get_settings, resolve_path
from .pairing import (
    build_payload,
    create_pairing,
    local_ipv4_addresses,
    primary_host,
    qr_ascii,
    qr_png,
)
from .state import get_user_store
from .users import UserStore


def _store() -> UserStore:
    return get_user_store()


def _prompt_password(confirm: bool = True) -> str:
    while True:
        first = getpass.getpass("Password: ")
        if not confirm:
            return first
        second = getpass.getpass("Confirm password: ")
        if first != second:
            print("Passwords do not match. Try again.")
            continue
        return first


def cmd_create_user(args: argparse.Namespace) -> int:
    store = _store()
    username = args.username
    password = args.password or _prompt_password()
    try:
        store.add(username, password, role=args.role)
    except ValueError as exc:
        print(f"Error: {exc}", file=sys.stderr)
        return 1
    print(f"Created user '{username}' (role={args.role}).")
    return 0


def cmd_reset_password(args: argparse.Namespace) -> int:
    store = _store()
    if not store.exists(args.username):
        print(f"Error: no such user '{args.username}'.", file=sys.stderr)
        return 1
    password = args.password or _prompt_password()
    try:
        store.set_password(args.username, password)
    except ValueError as exc:
        print(f"Error: {exc}", file=sys.stderr)
        return 1
    print(f"Password updated for '{args.username}'.")
    return 0


def cmd_list_users(args: argparse.Namespace) -> int:  # noqa: ARG001
    store = _store()
    users = store.all()
    if not users:
        print("No users configured.")
        return 0
    for user in users:
        print(f"- {user.username} (role={user.role}, created={user.created_at:%Y-%m-%d})")
    return 0


def cmd_pair(args: argparse.Namespace) -> int:
    settings = get_settings()
    if not settings.enable_pairing:
        print("Pairing is disabled (MM_ENABLE_PAIRING=false).", file=sys.stderr)
        return 1

    info = create_pairing(resolve_path(settings.pairing_file), settings.pairing_ttl_seconds)
    host = args.host or primary_host()
    payload = build_payload(host, settings.port, settings.service_name, info.code)

    print(f"Pairing code: {info.code}")
    print(f"Expires at:   {info.expires_at:%Y-%m-%d %H:%M:%SZ}")
    print(f"Payload:      {payload}")
    if len(local_ipv4_addresses()) > 1:
        print("Other addresses on this PC:")
        for ip in local_ipv4_addresses():
            print(f"  {build_payload(ip, settings.port, settings.service_name, info.code)}")

    ascii_qr = qr_ascii(payload)
    if ascii_qr:
        print()
        try:
            print(ascii_qr)
        except UnicodeEncodeError:
            print(qr_ascii(payload, unicode_ok=False) or ascii_qr)

    png = qr_png(payload)
    if png and not args.no_png:
        out = Path(args.out or "pairing-qr.png").resolve()
        out.write_bytes(png)
        print(f"\nSaved QR image to {out}")
    elif png is None:
        print("\n(Install 'segno' to render a QR image.)")
    return 0


def cmd_info(args: argparse.Namespace) -> int:  # noqa: ARG001
    settings = get_settings()
    print(f"Service name: {settings.service_name}")
    print(f"Listen:       {settings.host}:{settings.port}")
    print(f"Pairing:      {'on' if settings.enable_pairing else 'off'}")
    print(f"mDNS:         {'on' if settings.enable_mdns else 'off'}")
    print(f"Accounts:     {len(settings.load_accounts())}")
    print(f"Users:        {_store().count()}")
    print("Addresses:    " + ", ".join(local_ipv4_addresses() or ["127.0.0.1"]))
    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="app.cli", description="Macro Monitor bridge CLI")
    sub = parser.add_subparsers(dest="command", required=True)

    p_create = sub.add_parser("create-user", help="Create a login account")
    p_create.add_argument("username")
    p_create.add_argument("--password", default=None, help="Non-interactive password")
    p_create.add_argument("--role", default="owner")
    p_create.set_defaults(func=cmd_create_user)

    p_reset = sub.add_parser("reset-password", help="Change an account password")
    p_reset.add_argument("username")
    p_reset.add_argument("--password", default=None)
    p_reset.set_defaults(func=cmd_reset_password)

    p_list = sub.add_parser("list-users", help="List login accounts")
    p_list.set_defaults(func=cmd_list_users)

    p_pair = sub.add_parser("pair", help="Create a QR pairing code")
    p_pair.add_argument("--host", default=None, help="Override advertised host")
    p_pair.add_argument("--ttl", type=int, default=None, help="(reserved) code lifetime")
    p_pair.add_argument("--out", default="pairing-qr.png")
    p_pair.add_argument("--no-png", action="store_true")
    p_pair.set_defaults(func=cmd_pair)

    p_info = sub.add_parser("info", help="Show bridge configuration summary")
    p_info.set_defaults(func=cmd_info)
    return parser


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    return int(args.func(args))


if __name__ == "__main__":
    raise SystemExit(main())
